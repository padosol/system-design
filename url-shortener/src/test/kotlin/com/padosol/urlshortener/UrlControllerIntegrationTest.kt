package com.padosol.urlshortener

import com.padosol.urlshortener.repository.UrlRepository
import com.padosol.urlshortener.service.UrlService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.net.URI
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class UrlControllerIntegrationTest @Autowired constructor(
    val mockMvc: MockMvc,
    val urlService: UrlService,
    val urlRepository: UrlRepository,
    val redisTemplate: StringRedisTemplate,
) {

    // 테스트 독립성: 공유 컨테이너의 누수(시퀀스 행/Redis 키) 차단
    @AfterEach
    fun cleanup() {
        redisTemplate.keys("url:*")?.let { if (it.isNotEmpty()) redisTemplate.delete(it) }
        urlRepository.deleteAll()
    }

    @Test
    fun `단축한 뒤 해당 키로 접근하면 원본으로 302 리다이렉트된다`() {
        val longUrl = "https://example.com/some/very/long/path"

        val response = mockMvc.post("/api/v1/urls") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"longUrl":"$longUrl"}"""
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString

        val shortKey = extractShortKey(response)

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
        postLongUrl(""""ftp://example.com"""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `빈 longUrl은 400`() {
        postLongUrl("\"\"").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `공백뿐인 longUrl은 400`() {
        postLongUrl("\"   \"").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `longUrl 필드 누락은 400`() {
        mockMvc.post("/api/v1/urls") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `잘못된 JSON 본문은 400`() {
        mockMvc.post("/api/v1/urls") {
            contentType = MediaType.APPLICATION_JSON
            content = "{ broken json"
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `resolve 시 Redis 캐시에 적재된다`() {
        val shortKey = urlService.shorten("https://example.com/cache-test")
        urlService.resolve(shortKey)
        assertTrue(redisTemplate.hasKey("url:$shortKey"))
    }

    // 응답 JSON에서 shortUrl 필드를 찾아 마지막 path segment(키)를 추출 (문자열 split 대신)
    private fun extractShortKey(json: String): String {
        val shortUrl = Regex(""""shortUrl"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: error("응답에 shortUrl 없음: $json")
        return URI(shortUrl).path.substringAfterLast('/')
    }

    private fun postLongUrl(longUrlJsonValue: String) =
        mockMvc.post("/api/v1/urls") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"longUrl":$longUrlJsonValue}"""
        }
}
