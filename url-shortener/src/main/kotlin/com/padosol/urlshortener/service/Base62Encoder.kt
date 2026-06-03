package com.padosol.urlshortener.service

import org.springframework.stereotype.Component

/**
 * 숫자 id ↔ Base62 문자열 변환.
 * 카운터(시퀀스)에서 받은 id를 짧은 키로 인코딩한다.
 */
@Component
class Base62Encoder {

    private val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val base = alphabet.length.toLong() // 62

    fun encode(value: Long): String {
        require(value >= 0) { "value must be non-negative: $value" }
        if (value == 0L) return alphabet[0].toString()

        var n = value
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(alphabet[(n % base).toInt()])
            n /= base
        }
        return sb.reverse().toString()
    }

    fun decode(key: String): Long {
        var result = 0L
        for (c in key) {
            val idx = alphabet.indexOf(c)
            require(idx >= 0) { "invalid base62 char: $c" }
            result = result * base + idx
        }
        return result
    }
}
