package com.padosol.notification.service

import com.padosol.notification.config.ThrottleProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 피로도 한도 — Redis 슬라이딩 윈도우 카운터(설계 §4·§6-3). durable 원장이 아니라 *best-effort*.
 * 윈도우 내 발송 수가 한도 미만이면 1건을 기록하고 true(허용), 한도에 도달했으면 false(드롭).
 */
@Component
class ThrottleStore(
    private val redis: StringRedisTemplate,
    private val props: ThrottleProperties,
) {
    /** @param token 윈도우 멤버(중복 제거용 고유값, 예: outbox idempotencyKey). */
    fun tryAcquire(userId: Long, category: String, token: String): Boolean {
        val key = "throttle:$userId:$category"
        val now = System.currentTimeMillis()
        val windowStart = (now - props.windowSeconds * 1000).toDouble()
        val zset = redis.opsForZSet()

        zset.removeRangeByScore(key, 0.0, windowStart) // 윈도우 밖 정리
        if (zset.score(key, token) != null) return true // 같은 delivery 재시도 → 이미 허용됨(idempotent)

        val count = zset.size(key) ?: 0L
        if (count >= props.marketingLimit) return false

        zset.add(key, token, now.toDouble())
        redis.expire(key, Duration.ofSeconds(props.windowSeconds))
        return true
    }
}
