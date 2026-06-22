package com.padosol.notification.service

import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.OutboxStatus
import com.padosol.notification.repository.OutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 모니터링(설계 §6-5): outbox backlog 깊이 + 채널별 발송/실패/드롭 카운터.
 * backlog 급증이 곧 장애 신호다.
 */
@Component
class NotificationMetrics(
    private val registry: MeterRegistry,
    outbox: OutboxRepository,
) {
    init {
        Gauge.builder(BACKLOG) { outbox.countByStatus(OutboxStatus.PENDING).toDouble() }
            .description("미발행(PENDING) outbox 수")
            .register(registry)
    }

    fun sent(channel: Channel) = registry.counter(SENT, "channel", channel.name).increment()
    fun failed(channel: Channel) = registry.counter(FAILED, "channel", channel.name).increment()
    fun throttled(channel: Channel) = registry.counter(THROTTLED, "channel", channel.name).increment()

    companion object {
        const val BACKLOG = "notification.outbox.backlog"
        const val SENT = "notification.sent"
        const val FAILED = "notification.failed"
        const val THROTTLED = "notification.throttled"
    }
}
