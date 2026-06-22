package com.padosol.notification.security

import com.padosol.notification.config.SecurityProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException

/**
 * 서비스 간 인증(설계 §6-5). v1 API 는 유효한 `X-Api-Key` 가 있어야 한다(없으면 401).
 * 인증된 producerId 는 요청 attribute 로 넘겨 컨트롤러가 권한 판정에 쓴다(본문 값은 신뢰하지 않음).
 * 운영에선 mTLS/JWT 로 대체.
 */
@Component
class ApiKeyAuthFilter(private val security: SecurityProperties) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (!request.requestURI.startsWith("/v1/")) {
            filterChain.doFilter(request, response)
            return
        }
        val producer = security.producerOf(request.getHeader(API_KEY_HEADER))
        if (producer == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "유효한 $API_KEY_HEADER 가 필요합니다")
            return
        }
        request.setAttribute(PRODUCER_ATTR, producer)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val API_KEY_HEADER = "X-Api-Key"
        const val PRODUCER_ATTR = "producerId"
    }
}

/**
 * 권한 판정(설계 §6-3·§6-5). producer 는 허용된 category 와 maxPriority 안에서만 발송할 수 있다.
 * priority override 가 권한을 넘으면 403 — "template 기본값 + 권한 내 override" 규칙의 강제 지점.
 */
@Component
class AccessControl(private val security: SecurityProperties) {

    /** 낮음 → 높음 순위. */
    private val priorityRank = listOf("bulk", "normal", "high")

    fun authorize(producerId: String, category: String, priority: String?) {
        val perm = security.permissions[producerId]
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "권한 정보 없는 producer: $producerId")

        if (category !in perm.categories) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "category 권한 없음: $category")
        }
        if (priority != null) {
            val requested = priorityRank.indexOf(priority)
            val max = priorityRank.indexOf(perm.maxPriority)
            if (requested < 0 || max < 0 || requested > max) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "priority 권한 초과: $priority (max ${perm.maxPriority})")
            }
        }
    }
}
