package com.padosol.notification.security

import com.padosol.notification.config.SecurityProperties
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertFailsWith

/** 권한 판정은 순수 로직 — 컨테이너 없이 단위 검증(D3 분기 보강). */
class AccessControlTest {

    private val access = AccessControl(
        SecurityProperties(
            apiKeys = mapOf("k" to "p"),
            permissions = mapOf("p" to SecurityProperties.Permission(categories = listOf("marketing"), maxPriority = "normal")),
        ),
    )

    @Test
    fun `허용 category·priority 면 통과한다`() {
        access.authorize("p", "marketing", "normal") // 예외 없음
        access.authorize("p", "marketing", null)      // priority 미지정도 통과
    }

    @Test
    fun `권한 정보 없는 producer 는 403`() {
        assertFailsWith<ResponseStatusException> { access.authorize("unknown", "marketing", null) }
    }

    @Test
    fun `허용되지 않은 category 는 403`() {
        assertFailsWith<ResponseStatusException> { access.authorize("p", "transactional", null) }
    }

    @Test
    fun `알 수 없는 priority 문자열은 403`() {
        assertFailsWith<ResponseStatusException> { access.authorize("p", "marketing", "ultra") }
    }
}
