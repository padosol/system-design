package com.padosol.notification.service

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.NotificationDelivery
import com.padosol.notification.domain.NotificationRequest
import com.padosol.notification.domain.Outbox
import com.padosol.notification.repository.AppUserRepository
import com.padosol.notification.repository.DeviceRepository
import com.padosol.notification.repository.NotificationDeliveryRepository
import com.padosol.notification.repository.NotificationRequestRepository
import com.padosol.notification.repository.NotificationSettingRepository
import com.padosol.notification.repository.OutboxRepository
import com.padosol.notification.repository.TemplateRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/** 발송 요청 입력(컨트롤러 DTO → 서비스 경계). */
data class SendCommand(
    val userId: Long,
    val channel: Channel?,
    val templateId: String,
    val category: String,
    val priority: String?,
    val params: Map<String, String>,
    val producerId: String = "default",
    val dedupKey: String? = null,
)

/** 접수 결과. `deduplicated` 면 같은 (producerId, dedupKey)로 이미 접수된 요청을 그대로 반환한 것. */
data class AcceptResult(val requestId: Long, val deduplicated: Boolean)

data class DeliveryView(val channel: Channel, val target: String, val status: DeliveryStatus)
data class RequestStatus(val progress: String, val deliveries: List<DeliveryView>)

/**
 * 발송 접수 + 상태 조회(설계 §5·§6-1).
 * 라운드B: 동기 디스패치를 버리고 **접수(request+delivery+outbox 원자 커밋)** 와 **발행(OutboxRelay)** 을 분리한다.
 * 실제 발행은 [OutboxRelay] 가 담당하고, 컨트롤러가 접수 직후 저지연으로 한 번 트리거한다.
 */
@Service
class NotificationService(
    private val requests: NotificationRequestRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val acceptor: RequestAcceptor,
) {

    /**
     * 멱등 접수. (producerId, dedupKey)가 이미 있으면 새로 만들지 않고 기존 requestId 를 돌려준다.
     * 동시성: 사전 조회 + UNIQUE 제약 백스톱 — 경합에서 진 호출은 제약 위반을 잡아 기존 행을 재조회한다.
     */
    fun accept(cmd: SendCommand): AcceptResult {
        cmd.dedupKey?.let { key ->
            requests.findByProducerIdAndDedupKey(cmd.producerId, key)?.let {
                return AcceptResult(it.id!!, deduplicated = true)
            }
        }
        return try {
            AcceptResult(acceptor.create(cmd), deduplicated = false)
        } catch (e: DataIntegrityViolationException) {
            // 경합: 다른 트랜잭션이 같은 키를 먼저 커밋함 → 기존 행을 재조회.
            val key = cmd.dedupKey ?: throw e
            val existing = requests.findByProducerIdAndDedupKey(cmd.producerId, key) ?: throw e
            AcceptResult(existing.id!!, deduplicated = true)
        }
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
        // PII 마스킹(설계 §6-5): 상태조회 응답에 연락처/토큰 원문을 노출하지 않는다.
        return RequestStatus(progress, ds.map { DeliveryView(it.channel, Pii.mask(it.target), it.status) })
    }
}

/**
 * 접수 1건을 한 트랜잭션으로 영속화한다: request + delivery(QUEUED) + outbox(PENDING) 원자 커밋(설계 §6-1).
 * [NotificationService] 와 분리된 빈이라야 트랜잭션 프록시가 적용된다(self-invocation 회피).
 */
@Component
class RequestAcceptor(
    private val users: AppUserRepository,
    private val devices: DeviceRepository,
    private val settings: NotificationSettingRepository,
    private val templates: TemplateRepository,
    private val requests: NotificationRequestRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val outbox: OutboxRepository,
    private val renderer: TemplateRenderer,
) {
    /** @return 생성된 requestId. 같은 (producerId, dedupKey)가 이미 있으면 UNIQUE 위반 예외를 던진다. */
    @Transactional
    fun create(cmd: SendCommand): Long {
        val user = users.findById(cmd.userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "user 없음: ${cmd.userId}") }
        val template = templates.findById(cmd.templateId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "template 없음: ${cmd.templateId}") }

        val request = requests.save(
            NotificationRequest(
                userId = user.id!!,
                category = cmd.category,
                priority = cmd.priority ?: template.priority,
                producerId = cmd.producerId,
                dedupKey = cmd.dedupKey,
            ),
        )
        val message = renderer.render(template, cmd.params)

        val channels = if (cmd.channel != null) listOf(cmd.channel) else candidateChannels(user, cmd.category)
        for (channel in channels) {
            val enabled = settings.findByUserIdAndChannelAndCategory(user.id!!, channel, cmd.category)?.enabled ?: true
            val targets = targetsFor(user, channel)

            if (!enabled) {
                // opt-out: suppressed 로 기록하고 outbox 에 넣지 않는다(발송 안 함, F7).
                deliveries.save(delivery(request.id!!, channel, targets.firstOrNull() ?: "-", DeliveryStatus.SUPPRESSED))
                continue
            }
            for (target in targets) {
                val d = deliveries.save(delivery(request.id!!, channel, target, DeliveryStatus.QUEUED))
                outbox.save(
                    Outbox(
                        deliveryId = d.id!!,
                        channel = channel,
                        target = target,
                        subject = message.subject,
                        body = message.body,
                        idempotencyKey = "dlv-${d.id}", // delivery 단위 멱등키 — 릴레이 재발행 시 provider 가 흡수(B5)
                        userId = user.id!!,
                        category = cmd.category, // 발송 직전 throttle 판정용(설계 §6-3)
                    ),
                )
            }
        }
        return request.id!!
    }

    private fun candidateChannels(user: AppUser, category: String): List<Channel> =
        Channel.entries.filter { channel ->
            targetsFor(user, channel).isNotEmpty() &&
                (settings.findByUserIdAndChannelAndCategory(user.id!!, channel, category)?.enabled ?: true)
        }

    private fun targetsFor(user: AppUser, channel: Channel): List<String> = when (channel) {
        Channel.PUSH -> devices.findByUserIdAndActiveTrue(user.id!!).map { it.token }
        Channel.EMAIL -> listOfNotNull(user.email)
        Channel.SMS -> listOfNotNull(user.phone)
    }

    private fun delivery(requestId: Long, channel: Channel, target: String, status: DeliveryStatus) =
        NotificationDelivery(requestId = requestId, channel = channel, target = target, status = status)
}
