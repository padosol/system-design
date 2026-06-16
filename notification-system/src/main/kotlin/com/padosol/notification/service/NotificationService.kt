package com.padosol.notification.service

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.NotificationDelivery
import com.padosol.notification.domain.NotificationRequest
import com.padosol.notification.provider.NotificationProvider
import com.padosol.notification.repository.AppUserRepository
import com.padosol.notification.repository.DeviceRepository
import com.padosol.notification.repository.NotificationDeliveryRepository
import com.padosol.notification.repository.NotificationRequestRepository
import com.padosol.notification.repository.NotificationSettingRepository
import com.padosol.notification.repository.TemplateRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/** 발송 요청 입력(컨트롤러 DTO → 서비스 경계). */
data class SendCommand(
    val userId: Long,
    val channel: Channel?,
    val templateId: String,
    val category: String,
    val priority: String?,
    val params: Map<String, String>,
)

data class DeliveryView(val channel: Channel, val target: String, val status: DeliveryStatus)
data class RequestStatus(val progress: String, val deliveries: List<DeliveryView>)

/**
 * 단건 발송 파이프라인(설계 §5).
 * 라운드1은 큐 대신 **동기 디스패치**로 단순화한다(GOALS 전제). 큐/outbox/재시도는 라운드B에서.
 */
@Service
class NotificationService(
    private val users: AppUserRepository,
    private val devices: DeviceRepository,
    private val settings: NotificationSettingRepository,
    private val templates: TemplateRepository,
    private val requests: NotificationRequestRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val renderer: TemplateRenderer,
    providers: List<NotificationProvider>,
) {
    private val providerByChannel: Map<Channel, NotificationProvider> = providers.associateBy { it.channel }

    @Transactional
    fun send(cmd: SendCommand): Long {
        val user = users.findById(cmd.userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "user 없음: ${cmd.userId}") }
        val template = templates.findById(cmd.templateId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "template 없음: ${cmd.templateId}") }

        val request = requests.save(
            NotificationRequest(userId = user.id!!, category = cmd.category, priority = cmd.priority ?: template.priority),
        )
        val message = renderer.render(template, cmd.params)

        val channels = if (cmd.channel != null) listOf(cmd.channel) else candidateChannels(user, cmd.category)
        for (channel in channels) {
            val enabled = settings.findByUserIdAndChannelAndCategory(user.id!!, channel, cmd.category)?.enabled ?: true
            val targets = targetsFor(user, channel)

            if (!enabled) {
                // opt-out: 발송하지 않고 suppressed 로 기록(F7). provider 호출 0.
                deliveries.save(delivery(request.id!!, channel, targets.firstOrNull() ?: "-", DeliveryStatus.SUPPRESSED))
                continue
            }
            if (targets.isEmpty()) continue
            val provider = providerByChannel[channel] ?: continue

            for (target in targets) {
                val d = deliveries.save(delivery(request.id!!, channel, target, DeliveryStatus.QUEUED))
                provider.send(target, message)
                d.status = DeliveryStatus.SENT
                d.attemptCount = 1
                d.updatedAt = Instant.now()
                deliveries.save(d)
            }
        }
        return request.id!!
    }

    @Transactional(readOnly = true)
    fun status(requestId: Long): RequestStatus {
        val ds = deliveries.findByRequestId(requestId)
        val progress = when {
            ds.isEmpty() -> "pending"
            ds.all { it.status in DeliveryStatus.TERMINAL } -> "completed"
            ds.none { it.status in DeliveryStatus.TERMINAL } -> "pending"
            else -> "partial"
        }
        return RequestStatus(progress, ds.map { DeliveryView(it.channel, it.target, it.status) })
    }

    /** channel 미지정 시: 보낼 대상(연락처/디바이스)이 있고 enabled 인 채널만 선택(F5). */
    private fun candidateChannels(user: AppUser, category: String): List<Channel> =
        Channel.entries.filter { channel ->
            targetsFor(user, channel).isNotEmpty() &&
                (settings.findByUserIdAndChannelAndCategory(user.id!!, channel, category)?.enabled ?: true)
        }

    /** 채널별 수신처: PUSH=활성 디바이스 토큰들(멀티디바이스 fan-out, F6), EMAIL=이메일, SMS=전화. */
    private fun targetsFor(user: AppUser, channel: Channel): List<String> = when (channel) {
        Channel.PUSH -> devices.findByUserIdAndActiveTrue(user.id!!).map { it.token }
        Channel.EMAIL -> listOfNotNull(user.email)
        Channel.SMS -> listOfNotNull(user.phone)
    }

    private fun delivery(requestId: Long, channel: Channel, target: String, status: DeliveryStatus) =
        NotificationDelivery(requestId = requestId, channel = channel, target = target, status = status)
}
