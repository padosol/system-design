package com.padosol.notification.service

/** 연락처/토큰 마스킹(설계 §6-5 PII). 상태조회 응답·로그에서 원문을 노출하지 않는다. */
object Pii {
    fun mask(target: String): String = when {
        "@" in target -> target.take(1) + "***" + target.substring(target.indexOf("@")) // 이메일
        target.startsWith("+") || target.all { it.isDigit() } -> tail(target)            // 전화
        else -> if (target.length <= 4) "****" else target.take(4) + "***"               // device token 등
    }

    private fun tail(s: String) = if (s.length <= 4) "****" else s.take(3) + "****" + s.takeLast(2)
}
