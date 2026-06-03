package com.padosol.urlshortener.controller

import com.padosol.urlshortener.dto.CreateUrlRequest
import com.padosol.urlshortener.dto.CreateUrlResponse
import com.padosol.urlshortener.service.UrlService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI

@RestController
class UrlController(
    private val urlService: UrlService,
) {

    @PostMapping("/api/v1/urls")
    @ResponseStatus(HttpStatus.CREATED)
    fun shorten(@Valid @RequestBody request: CreateUrlRequest): CreateUrlResponse {
        validateUrl(request.longUrl)
        val shortKey = urlService.shorten(request.longUrl)
        return CreateUrlResponse(shortUrl = "$BASE_URL/$shortKey")
    }

    @GetMapping("/{shortKey}")
    fun redirect(@PathVariable shortKey: String): ResponseEntity<Void> {
        val longUrl = urlService.resolve(shortKey)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "단축 URL을 찾을 수 없습니다: $shortKey")

        return ResponseEntity.status(HttpStatus.FOUND) // 302 (설계 문서: 클릭 통계 여지)
            .location(URI.create(longUrl))
            .build()
    }

    private fun validateUrl(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "http(s):// 로 시작하는 URL이어야 합니다")
        }
    }

    companion object {
        private const val BASE_URL = "http://localhost:8080"
    }
}
