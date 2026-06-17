package com.padosol.notification.provider

import com.padosol.notification.domain.Channel
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/** 렌더링된 최종 메시지(설계 §5: 큐 메시지에 담는 콘텐츠). */
data class RenderedMessage(val subject: String?, val body: String)

/** 서드파티로 나간 발송 기록 — 테스트 검증용. */
data class SentMessage(val channel: Channel, val target: String, val message: RenderedMessage)

/**
 * 서드파티 발송 추상화(채널별 어댑터 seam).
 * 라운드1은 FCM/Twilio/SendGrid 대신 호출을 기록하는 mock 으로 구현한다(설계 §6 deep-dive·라운드2에서 실 어댑터로 교체).
 */
interface NotificationProvider {
    val channel: Channel
    fun send(target: String, message: RenderedMessage)
}

/** 세 채널 mock provider 가 공유하는 발송 레코더. 테스트가 호출 횟수를 검증한다. */
@Component
class RecordingProviders {
    val sent = CopyOnWriteArrayList<SentMessage>()
    fun clear() = sent.clear()
    fun countByChannel(channel: Channel) = sent.count { it.channel == channel }
}

@Component
class PushProvider(private val recorder: RecordingProviders) : NotificationProvider {
    override val channel = Channel.PUSH
    override fun send(target: String, message: RenderedMessage) {
        recorder.sent.add(SentMessage(channel, target, message))
    }
}

@Component
class SmsProvider(private val recorder: RecordingProviders) : NotificationProvider {
    override val channel = Channel.SMS
    override fun send(target: String, message: RenderedMessage) {
        recorder.sent.add(SentMessage(channel, target, message))
    }
}

@Component
class EmailProvider(private val recorder: RecordingProviders) : NotificationProvider {
    override val channel = Channel.EMAIL
    override fun send(target: String, message: RenderedMessage) {
        recorder.sent.add(SentMessage(channel, target, message))
    }
}
