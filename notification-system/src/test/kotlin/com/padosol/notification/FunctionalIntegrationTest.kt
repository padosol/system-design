package com.padosol.notification

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Device
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.Template
import com.padosol.notification.provider.PushProvider
import com.padosol.notification.repository.AppUserRepository
import com.padosol.notification.repository.DeviceRepository
import com.padosol.notification.repository.NotificationDeliveryRepository
import com.padosol.notification.repository.NotificationRequestRepository
import com.padosol.notification.repository.NotificationSettingRepository
import com.padosol.notification.repository.TemplateRepository
import com.padosol.notification.service.RegistrationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals

/**
 * GOALS.md 기능 라운드 — 단일 채널(푸시) 기능 완료 조건을 통합테스트로 검증한다.
 * 서드파티는 PushProvider(mock)로 호출 횟수를 관측한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class FunctionalIntegrationTest @Autowired constructor(
    val mockMvc: MockMvc,
    val users: AppUserRepository,
    val devices: DeviceRepository,
    val settings: NotificationSettingRepository,
    val templates: TemplateRepository,
    val requests: NotificationRequestRepository,
    val deliveries: NotificationDeliveryRepository,
    val registration: RegistrationService,
    val push: PushProvider,
) {

    @AfterEach
    fun cleanup() {
        push.clear()
        deliveries.deleteAll()
        requests.deleteAll()
        settings.deleteAll()
        devices.deleteAll()
        templates.deleteAll()
        users.deleteAll()
    }

    @Test
    fun `F1 같은 token 재등록은 upsert - 중복 행 0`() {
        val user = seedUser()
        val body = """{"userId":$user,"token":"tok-1","platform":"ios"}"""

        repeat(2) {
            mockMvc.post("/v1/devices") {
                header("X-Api-Key", "test-key")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isCreated() } }
        }

        assertEquals(1, devices.count(), "같은 token 재등록은 중복 행을 만들지 않아야 한다")
    }

    @Test
    fun `F2 설정 변경(PUT)이 후속 발송 판정에 적용된다`() {
        val user = seedUser()
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "marketing", body = "hi"))

        mockMvc.put("/v1/users/$user/settings") {
            header("X-Api-Key", "test-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"marketing","enabled":false}"""
        }.andExpect { status { isOk() } }

        val requestId = postNotification(user, "T", "marketing")

        assertEquals(0, push.count(), "opt-out 설정이 발송을 막아야 한다")
        assertEquals(DeliveryStatus.SUPPRESSED, deliveries.findByRequestId(requestId).single().status)
    }

    @Test
    fun `F3 단건 발송 - 202와 mock provider 1회 호출, delivery SENT`() {
        val user = seedUser()
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hello"))

        val requestId = postNotification(user, "T", "transactional")

        assertEquals(1, push.count())
        val ds = deliveries.findByRequestId(requestId)
        assertEquals(1, ds.size)
        assertEquals(DeliveryStatus.SENT, ds.single().status)
    }

    @Test
    fun `F4 템플릿 렌더링 - params 치환본이 provider로 전달`() {
        val user = seedUser()
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "transactional", body = "Hi {{name}}, order {{orderId}}"))

        postNotification(user, "T", "transactional", params = """{"name":"Kim","orderId":"A1"}""")

        assertEquals("Hi Kim, order A1", push.sent.single().message.body)
    }

    @Test
    fun `F6 멀티디바이스 - device 2개면 delivery 2건`() {
        val user = seedUser()
        devices.save(Device(userId = user, token = "t1", platform = "ios"))
        devices.save(Device(userId = user, token = "t2", platform = "android"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val requestId = postNotification(user, "T", "transactional")

        val ds = deliveries.findByRequestId(requestId)
        assertEquals(2, ds.size)
        assertEquals(2, push.count())
        assertEquals(setOf("t1", "t2"), ds.map { it.target }.toSet())
    }

    @Test
    fun `F7 opt-out 카테고리는 suppressed, provider 호출 0`() {
        val user = seedUser()
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "marketing", body = "hi"))
        registration.updateSetting(user, "marketing", false)

        val requestId = postNotification(user, "T", "marketing")

        assertEquals(0, push.count())
        assertEquals(DeliveryStatus.SUPPRESSED, deliveries.findByRequestId(requestId).single().status)
    }

    @Test
    fun `F8 GET 상태조회 - progress completed와 delivery별 status`() {
        val user = seedUser()
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val requestId = postNotification(user, "T", "transactional")

        mockMvc.get("/v1/notifications/$requestId") {
            header("X-Api-Key", "test-key")
        }.andExpect {
            status { isOk() }
            jsonPath("$.progress") { value("completed") }
            jsonPath("$.deliveries.length()") { value(1) }
            jsonPath("$.deliveries[0].status") { value("SENT") }
        }
    }

    // --- helpers ---

    private fun seedUser(): Long = users.save(AppUser()).id!!

    private fun postNotification(userId: Long, templateId: String, category: String, params: String = "{}"): Long {
        val body = """{"userId":$userId,"templateId":"$templateId","category":"$category","params":$params}"""
        val response = mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "test-key")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isAccepted() } // 202
        }.andReturn().response.contentAsString
        return Regex(""""requestId"\s*:\s*(\d+)""").find(response)?.groupValues?.get(1)?.toLong()
            ?: error("응답에 requestId 없음: $response")
    }
}
