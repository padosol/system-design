package com.padosol.urlshortener.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base62EncoderTest {

    private val encoder = Base62Encoder()

    @Test
    fun `0은 0으로 인코딩된다`() {
        assertEquals("0", encoder.encode(0))
    }

    // ── 알파벳 경계 (순서: 숫자 0-9 → 대문자 A-Z → 소문자 a-z) ───────────────
    @Test
    fun `9는 마지막 숫자, 10은 첫 대문자 A로 전이된다`() {
        assertEquals("9", encoder.encode(9))
        assertEquals("A", encoder.encode(10))
    }

    @Test
    fun `35는 마지막 대문자 Z, 36은 첫 소문자 a로 전이된다`() {
        assertEquals("Z", encoder.encode(35))
        assertEquals("a", encoder.encode(36))
    }

    @Test
    fun `61은 마지막 소문자 z`() {
        assertEquals("z", encoder.encode(61))
    }

    @Test
    fun `decode는 대소문자를 구분한다`() {
        assertEquals(10L, encoder.decode("A"))
        assertEquals(36L, encoder.decode("a"))
    }

    // ── 자리수 전이 경계 ────────────────────────────────────────────────────
    @Test
    fun `62는 두 자리 10으로 넘어간다`() {
        assertEquals("10", encoder.encode(62))
    }

    @Test
    fun `62의 제곱 경계에서 두 자리에서 세 자리로 전이된다`() {
        assertEquals("zz", encoder.encode(3843))   // 62^2 - 1 (두 자리 최댓값)
        assertEquals("100", encoder.encode(3844))  // 62^2     (세 자리 시작)
    }

    @Test
    fun `encode-decode 왕복 변환이 일치한다`() {
        val samples = listOf(
            1L, 61L, 62L, 63L, 12_345L,
            56_800_235_583L,   // 62^6 - 1 = "zzzzzz" (6자리 최댓값)
            Long.MAX_VALUE,
        )
        for (v in samples) {
            assertEquals(v, encoder.decode(encoder.encode(v)), "왕복 실패: $v")
        }
    }

    // ── Long 경계 / 오버플로 (정확한 경계) ──────────────────────────────────
    // encode(Long.MAX_VALUE) == "AzL8n0Y58m7" (유효한 11자 상한)
    @Test
    fun `Long MAX 키는 정확히 디코딩된다`() {
        assertEquals(Long.MAX_VALUE, encoder.decode("AzL8n0Y58m7"))
    }

    @Test
    fun `Long MAX를 넘으면 ArithmeticException - 11자도 안전하지 않다`() {
        // "AzL8n0Y58m8" = Long.MAX + 1 (최소 오버플로 경계)
        assertFailsWith<ArithmeticException> { encoder.decode("AzL8n0Y58m8") }
        // 같은 11자라도 값이 크면 오버플로 (자리수가 아니라 값이 경계)
        assertFailsWith<ArithmeticException> { encoder.decode("ZZZZZZZZZZZ") }
    }

    // ── 예외 계약 ──────────────────────────────────────────────────────────
    @Test
    fun `음수 인코딩은 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { encoder.encode(-1) }
    }

    @Test
    fun `알파벳에 없는 문자 디코딩은 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { encoder.decode("abc!") }
    }

    @Test
    fun `빈 문자열 디코딩은 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { encoder.decode("") }
    }

    // ── leading-zero 정규화 (decode는 단사가 아님) ─────────────────────────
    @Test
    fun `앞자리 0은 정규화되어 decode-encode는 identity가 아니다`() {
        assertEquals(1L, encoder.decode("01"))
        assertEquals(0L, encoder.decode("00"))
        // "01" -> 1 -> "1" : 앞의 0이 사라진다
        assertEquals("1", encoder.encode(encoder.decode("01")))
    }
}
