package com.padosol.notification.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 백오프는 순수 함수 — 컨테이너 없이 단위 검증(B4의 '지수 증가' 근거). */
class BackoffTest {

    @Test
    fun `지수적으로 증가한다`() {
        assertEquals(100, Backoff.delayMillis(100, 1))
        assertEquals(200, Backoff.delayMillis(100, 2))
        assertEquals(400, Backoff.delayMillis(100, 3))
        assertEquals(800, Backoff.delayMillis(100, 4))
    }

    @Test
    fun `base 가 0이면 항상 0 (즉시 재시도)`() {
        assertEquals(0, Backoff.delayMillis(0, 1))
        assertEquals(0, Backoff.delayMillis(0, 5))
    }
}
