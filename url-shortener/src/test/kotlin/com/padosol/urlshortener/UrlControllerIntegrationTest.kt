package com.padosol.urlshortener

import com.padosol.urlshortener.service.UrlService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class UrlControllerIntegrationTest @Autowired constructor(
    val mockMvc: MockMvc,
    val urlService: UrlService,
    val redisTemplate: StringRedisTemplate,
) {

    @Test
    fun `단축한 뒤 해당 키로 접근하면 원본으로 302 리다이렉트된다`() {
        val longUrl = "https://example.com/some/very/long/path"

        val response = mockMvc.post("/api/v1/urls") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"longUrl":"$longUrl"}"""
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString

        val shortKey = response.substringAfterLast("/").trimEnd('"', '}')

        mockMvc.get("/$shortKey").andExpect {
            status { isFound() } // 302
            header { string("Location", longUrl) }
        }
    }

    @Test
    fun `없는 키로 접근하면 404`() {
        mockMvc.get("/nonexistent").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `http가 아닌 URL은 400`() {
        mockMvc.post("/api/v1/urls") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"longUrl":"ftp://example.com"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `resolve 시 Redis 캐시에 적재된다`() {
        val shortKey = urlService.shorten("https://example.com/cache-test")
        urlService.resolve(shortKey)
        assertTrue(redisTemplate.hasKey("url:$shortKey"))
    }
}
