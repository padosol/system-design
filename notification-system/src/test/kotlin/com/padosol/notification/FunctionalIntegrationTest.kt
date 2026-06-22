package com.padosol.notification

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.Device
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.Template
import com.padosol.notification.provider.RecordingProviders
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
 * GOALS.md 기능 1라운드 — F1~F8 정량 완료 조건을 통합테스트로 검증한다.
 * 서드파티는 RecordingProviders(mock)로 호출 횟수를 관측한다.
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
    val recorder: RecordingProviders,
) {

    @AfterEach
    fun cleanup() {
        recorder.clear()
        deliveries.deleteAll()
        requests.deleteAll()
        settings.deleteAll()
        devices.deleteAll()
        templates.deleteAll()
        users.deleteAll()
    }

    @Test
    fun `F1 같은 token 재등록은 upsert - 중복 행 0`() {
        val user = users.save(AppUser(email = "a@b.com"))
        val body = """{"userId":${user.id},"token":"tok-1","platform":"ios"}"""

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
        val user = seedUser(email = "a@b.com")
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "marketing", body = "hi"))

        mockMvc.put("/v1/users/$user/settings") {
            header("X-Api-Key", "test-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"channel":"PUSH","category":"marketing","enabled":false}"""
        }.andExpect { status { isOk() } }

        val requestId = postNotification(user, "PUSH", "T", "marketing")

        assertEquals(0, recorder.countByChannel(Channel.PUSH), "opt-out 설정이 발송을 막아야 한다")
        assertEquals(DeliveryStatus.SUPPRESSED, deliveries.findByRequestId(requestId).single().status)
    }

    @Test
    fun `F3 단건 발송 - 202와 mock provider 1회 호출, delivery SENT`() {
        val user = seedUser(email = "a@b.com")
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hello"))

        val requestId = postNotification(user, "PUSH", "T", "transactional")

        assertEquals(1, recorder.countByChannel(Channel.PUSH))
        val ds = deliveries.findByRequestId(requestId)
        assertEquals(1, ds.size)
        assertEquals(DeliveryStatus.SENT, ds.single().status)
    }

    @Test
    fun `F4 템플릿 렌더링 - params 치환본이 provider로 전달`() {
        val user = seedUser(email = "a@b.com")
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "transactional", body = "Hi {{name}}, order {{orderId}}"))

        postNotification(user, "PUSH", "T", "transactional", params = """{"name":"Kim","orderId":"A1"}""")

        assertEquals("Hi Kim, order A1", recorder.sent.single().message.body)
    }

    @Test
    fun `F5 channel 미지정 - enabled 채널마다 발송 (push+email=2)`() {
        val user = seedUser(email = "a@b.com") // 이메일 있음, 전화 없음
        devices.save(Device(userId = user, token = "tok", platform = "ios")) // push 대상
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val requestId = postNotification(user, channel = null, templateId = "T", category = "transactional")

        val ds = deliveries.findByRequestId(requestId)
        assertEquals(2, ds.size)
        assertEquals(setOf(Channel.PUSH, Channel.EMAIL), ds.map { it.channel }.toSet())
        assertEquals(2, recorder.sent.size)
    }

    @Test
    fun `F6 멀티디바이스 - device 2개면 push delivery 2건`() {
        val user = seedUser(email = "a@b.com")
        devices.save(Device(userId = user, token = "t1", platform = "ios"))
        devices.save(Device(userId = user, token = "t2", platform = "android"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val requestId = postNotification(user, "PUSH", "T", "transactional")

        val ds = deliveries.findByRequestId(requestId)
        assertEquals(2, ds.size)
        assertEquals(2, recorder.countByChannel(Channel.PUSH))
        assertEquals(setOf("t1", "t2"), ds.map { it.target }.toSet())
    }

    @Test
    fun `F7 opt-out 채널은 suppressed, provider 호출 0`() {
        val user = seedUser(email = "a@b.com")
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "marketing", body = "hi"))
        registration.updateSetting(user, Channel.PUSH, "marketing", false)

        val requestId = postNotification(user, "PUSH", "T", "marketing")

        assertEquals(0, recorder.countByChannel(Channel.PUSH))
        assertEquals(DeliveryStatus.SUPPRESSED, deliveries.findByRequestId(requestId).single().status)
    }

    @Test
    fun `F8 GET 상태조회 - progress completed와 delivery별 status`() {
        val user = seedUser(email = "a@b.com")
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val requestId = postNotification(user, "PUSH", "T", "transactional")

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

    private fun seedUser(email: String? = null, phone: String? = null): Long =
        users.save(AppUser(email = email, phone = phone)).id!!

    private fun postNotification(
        userId: Long,
        channel: String?,
        templateId: String,
        category: String,
        params: String = "{}",
    ): Long {
        val channelField = if (channel != null) "\"channel\":\"$channel\"," else ""
        val body = """{"userId":$userId,$channelField"templateId":"$templateId","category":"$category","params":$params}"""
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
