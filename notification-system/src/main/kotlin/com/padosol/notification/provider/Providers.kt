package com.padosol.notification.provider

import com.padosol.notification.domain.Channel
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 렌더링된 최종 메시지(설계 §5: 큐 메시지에 담는 콘텐츠). */
data class RenderedMessage(val subject: String?, val body: String)

/** 서드파티로 나간 발송 기록 — 테스트 검증용. */
data class SentMessage(val channel: Channel, val target: String, val message: RenderedMessage)

/** provider 발송 실패(5xx 등). 릴레이가 잡아 재시도/DLQ 한다. */
class ProviderSendException(message: String) : RuntimeException(message)

/**
 * 서드파티 발송 추상화(채널별 어댑터 seam).
 * 라운드1~B는 FCM/Twilio/SendGrid 대신 호출을 기록하는 mock 으로 구현한다(라운드2에서 실 어댑터로 교체).
 *
 * `idempotencyKey` 는 provider 측 멱등(설계 §6-1 ③) 모사용 — 같은 키 재발행은 실발송 1회로 흡수된다.
 */
interface NotificationProvider {
    val channel: Channel
    fun send(target: String, message: RenderedMessage, idempotencyKey: String)
}

/**
 * 세 채널 mock provider 가 공유하는 상태:
 * - `sent`        실발송 기록(테스트가 횟수 검증)
 * - idempotency   같은 `idempotencyKey` 재발행은 1회로 흡수(provider 측 멱등 모사, B5)
 * - `failingChannels` 해당 채널 발송 시 예외 주입(5xx 모사, B3·B4)
 */
@Component
class RecordingProviders {
    val sent = CopyOnWriteArrayList<SentMessage>()
    val failingChannels: MutableSet<Channel> = ConcurrentHashMap.newKeySet()
    private val seenKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** provider 발송 시도. 실패 채널이면 예외, 같은 키는 dedup(실발송 안 함). */
    fun dispatch(channel: Channel, target: String, message: RenderedMessage, idempotencyKey: String) {
        if (channel in failingChannels) throw ProviderSendException("provider 실패 주입: $channel")
        if (!seenKeys.add(idempotencyKey)) return // 이미 보낸 키 → provider 측 멱등으로 흡수
        sent.add(SentMessage(channel, target, message))
    }

    fun countByChannel(channel: Channel) = sent.count { it.channel == channel }

    fun clear() {
        sent.clear()
        seenKeys.clear()
        failingChannels.clear()
    }
}

@Component
class PushProvider(private val recorder: RecordingProviders) : NotificationProvider {
    override val channel = Channel.PUSH
    override fun send(target: String, message: RenderedMessage, idempotencyKey: String) =
        recorder.dispatch(channel, target, message, idempotencyKey)
}

@Component
class SmsProvider(private val recorder: RecordingProviders) : NotificationProvider {
    override val channel = Channel.SMS
    override fun send(target: String, message: RenderedMessage, idempotencyKey: String) =
        recorder.dispatch(channel, target, message, idempotencyKey)
}

@Component
class EmailProvider(private val recorder: RecordingProviders) : NotificationProvider {
    override val channel = Channel.EMAIL
    override fun send(target: String, message: RenderedMessage, idempotencyKey: String) =
        recorder.dispatch(channel, target, message, idempotencyKey)
}
