package com.padosol.notification.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 피로도 제어 설정(설계 §6-3). */
@ConfigurationProperties("notification.throttle")
data class ThrottleProperties(
    val marketingLimit: Int = 3,
    val windowSeconds: Long = 86_400,
    /** 한도 면제 카테고리(OTP·결제 등 필수 알림). */
    val essentialCategories: List<String> = listOf("transactional", "otp"),
) {
    fun isEssential(category: String) = category in essentialCategories
}

/** 보안 설정(설계 §6-5). 운영에선 시크릿/외부 설정으로 주입. */
@ConfigurationProperties("notification.security")
data class SecurityProperties(
    /** X-Api-Key → producerId. */
    val apiKeys: Map<String, String> = emptyMap(),
    /** producerId → 권한. */
    val permissions: Map<String, Permission> = emptyMap(),
) {
    data class Permission(
        val categories: List<String> = emptyList(),
        val maxPriority: String = "bulk",
    )

    fun producerOf(apiKey: String?): String? = apiKey?.let { apiKeys[it] }
}
