package com.padosol.notification.service

import com.padosol.notification.config.ThrottleProperties
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.Outbox
import com.padosol.notification.domain.OutboxStatus
import com.padosol.notification.provider.NotificationProvider
import com.padosol.notification.provider.RenderedMessage
import com.padosol.notification.repository.NotificationDeliveryRepository
import com.padosol.notification.repository.OutboxRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** 재시도 백오프(순수 함수). base * 2^(attempt-1) — attempt 는 1부터. */
object Backoff {
    fun delayMillis(baseMillis: Long, attempt: Int): Long {
        if (baseMillis <= 0L || attempt <= 0) return 0L
        val shift = (attempt - 1).coerceAtMost(20) // 오버플로 가드
        return baseMillis shl shift
    }
}

/**
 * Outbox 릴레이(설계 §6-1 ②③④).
 * PENDING(due) outbox 를 provider 로 발행한다. 성공 → PUBLISHED + delivery SENT,
 * 실패 → 지수 백오프 재시도, maxRetry 초과 시 DLQ + delivery FAILED.
 * provider 에 idempotencyKey 를 넘겨 재발행(크래시 후 회수)이 실발송 중복으로 이어지지 않게 한다(B5).
 *
 * 실제로는 폴러/CDC + 스케줄러로 돌지만(설계), 여기서는 호출 시 1배치 발행하는 [publishPending] 으로 둔다.
 */
@Component
class OutboxRelay(
    private val outbox: OutboxRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val throttle: ThrottleStore,
    private val throttleProps: ThrottleProperties,
    private val metrics: NotificationMetrics,
    providers: List<NotificationProvider>,
    @param:Value("\${notification.outbox.max-retry:5}") private val maxRetry: Int,
    @param:Value("\${notification.outbox.base-backoff-millis:1000}") private val baseBackoffMillis: Long,
) {
    private val providerByChannel = providers.associateBy { it.channel }

    /** due 한 PENDING outbox 를 최대 limit 개 발행한다. @return 발행 성공 건수. */
    @Transactional
    fun publishPending(limit: Int = 100): Int {
        val due = outbox.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            OutboxStatus.PENDING,
            Instant.now(),
            PageRequest.of(0, limit),
        )
        var published = 0
        for (row in due) {
            if (publishOne(row)) published++
        }
        return published
    }

    private fun publishOne(row: Outbox): Boolean {
        val provider = providerByChannel[row.channel] ?: return false

        // 발송 직전 피로도 재검사(설계 §6-3). 필수 카테고리는 면제, 한도 초과 마케팅은 드롭.
        if (!throttleProps.isEssential(row.category) &&
            !throttle.tryAcquire(row.userId, row.category, row.idempotencyKey)
        ) {
            row.status = OutboxStatus.DROPPED
            outbox.save(row)
            updateDelivery(row.deliveryId, DeliveryStatus.THROTTLED)
            metrics.throttled(row.channel)
            return false
        }

        return try {
            provider.send(row.target, RenderedMessage(row.subject, row.body), row.idempotencyKey)
            row.status = OutboxStatus.PUBLISHED
            row.publishedAt = Instant.now()
            outbox.save(row)
            updateDelivery(row.deliveryId, DeliveryStatus.SENT)
            metrics.sent(row.channel)
            true
        } catch (e: Exception) {
            row.attemptCount += 1
            if (row.attemptCount >= maxRetry) {
                row.status = OutboxStatus.DLQ // 재시도 소진 → 격리(설계 §6-1 ④)
                updateDelivery(row.deliveryId, DeliveryStatus.FAILED)
            } else {
                row.nextAttemptAt = Instant.now().plus(Duration.ofMillis(Backoff.delayMillis(baseBackoffMillis, row.attemptCount)))
            }
            outbox.save(row)
            metrics.failed(row.channel)
            false
        }
    }

    private fun updateDelivery(deliveryId: Long, status: DeliveryStatus) {
        deliveries.findById(deliveryId).ifPresent {
            it.status = status
            it.attemptCount += 1
            it.updatedAt = Instant.now()
            deliveries.save(it)
        }
    }
}
