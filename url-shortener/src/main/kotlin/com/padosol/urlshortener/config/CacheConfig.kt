package com.padosol.urlshortener.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class CacheConfig {

    /**
     * L1 인앱 캐시: shortKey -> longUrl.
     * 매핑은 불변이므로 staleness가 없다. (Redis 왕복을 줄이는 near-cache)
     */
    @Bean
    fun shortKeyCache(): Cache<String, String> =
        Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build()
}
