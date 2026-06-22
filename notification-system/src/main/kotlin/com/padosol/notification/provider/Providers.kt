package com.padosol.notification.provider

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 렌더링된 최종 메시지(큐 메시지에 담는 콘텐츠). */
data class RenderedMessage(val subject: String?, val body: String)

/** 푸시로 나간 발송 기록 — 테스트 검증용. */
data class SentMessage(val target: String, val message: RenderedMessage)

/** provider 발송 실패(5xx 등). 릴레이가 잡아 재시도/DLQ 한다. */
class ProviderSendException(message: String) : RuntimeException(message)

/**
 * 푸시 발송 어댑터(APNs/FCM seam). 라운드2에서 실 어댑터로 교체.
 * 지금은 호출을 기록하는 mock:
 * - `sent`        실발송 기록(테스트가 횟수 검증)
 * - idempotency   같은 `idempotencyKey` 재발행은 1회로 흡수(provider 측 멱등, B5)
 * - `fail`        발송 시 예외 주입(5xx 모사, B3·B4)
 */
@Component
class PushProvider {
    val sent = CopyOnWriteArrayList<SentMessage>()

    @Volatile
    var fail = false

    private val seenKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun send(target: String, message: RenderedMessage, idempotencyKey: String) {
        if (fail) throw ProviderSendException("provider 실패 주입")
        if (!seenKeys.add(idempotencyKey)) return // 이미 보낸 키 → provider 측 멱등으로 흡수
        sent.add(SentMessage(target, message))
    }

    fun count() = sent.size

    fun clear() {
        sent.clear()
        seenKeys.clear()
        fail = false
    }
}
