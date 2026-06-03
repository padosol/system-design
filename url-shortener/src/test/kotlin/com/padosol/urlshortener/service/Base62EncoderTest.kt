package com.padosol.urlshortener.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class Base62EncoderTest {

    private val encoder = Base62Encoder()

    @Test
    fun `0은 0으로 인코딩된다`() {
        assertEquals("0", encoder.encode(0))
    }

    @Test
    fun `61은 한 자리 마지막 문자 z`() {
        assertEquals("z", encoder.encode(61))
    }

    @Test
    fun `62는 두 자리 10으로 넘어간다`() {
        assertEquals("10", encoder.encode(62))
    }

    @Test
    fun `encode-decode 왕복 변환이 일치한다`() {
        val samples = listOf(1L, 61L, 62L, 63L, 12_345L, 56_800_235_583L, Long.MAX_VALUE)
        for (v in samples) {
            assertEquals(v, encoder.decode(encoder.encode(v)), "왕복 실패: $v")
        }
    }
}
