package com.padosol.urlshortener.service

import com.padosol.urlshortener.domain.Url
import com.padosol.urlshortener.repository.UrlRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
class UrlService(
    private val urlRepository: UrlRepository,
    private val base62Encoder: Base62Encoder,
    private val redisTemplate: StringRedisTemplate,
) {

    /** 긴 URL을 받아 짧은 키를 생성한다. (카운터 + Base62) */
    @Transactional
    fun shorten(longUrl: String): String {
        val id = urlRepository.nextId()
        val shortKey = base62Encoder.encode(id)
        urlRepository.save(Url(id = id, shortKey = shortKey, longUrl = longUrl))
        return shortKey
    }

    /** 짧은 키로 원본 URL을 찾는다. 캐시 우선(cache-aside), 없으면 null. */
    @Transactional(readOnly = true)
    fun resolve(shortKey: String): String? {
        redisTemplate.opsForValue().get(cacheKey(shortKey))?.let { return it }

        val longUrl = urlRepository.findByShortKey(shortKey)?.longUrl ?: return null
        redisTemplate.opsForValue().set(cacheKey(shortKey), longUrl, CACHE_TTL)
        return longUrl
    }

    private fun cacheKey(shortKey: String) = "url:$shortKey"

    companion object {
        private val CACHE_TTL = Duration.ofHours(1)
    }
}
