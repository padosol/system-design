package com.padosol.notification.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** PII 마스킹은 순수 함수 — 컨테이너 없이 단위 검증(D4 근거). */
class PiiTest {

    @Test
    fun `이메일은 첫 글자만 남기고 도메인은 유지`() {
        assertEquals("a***@example.com", Pii.mask("alice@example.com"))
    }

    @Test
    fun `전화는 앞 3·뒤 2만 남긴다`() {
        assertEquals("010****34", Pii.mask("01000001234"))
    }

    @Test
    fun `device token 은 앞 4글자만`() {
        assertEquals("abcd***", Pii.mask("abcdef0123"))
    }

    @Test
    fun `짧은 값은 전부 마스킹된다`() {
        assertEquals("****", Pii.mask("12")) // 짧은 전화
        assertEquals("****", Pii.mask("ab")) // 짧은 토큰
    }
}
