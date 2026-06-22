package com.padosol.notification

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.Device
import com.padosol.notification.domain.OutboxStatus
import com.padosol.notification.domain.Template
import com.padosol.notification.provider.PushProvider
import com.padosol.notification.repository.AppUserRepository
import com.padosol.notification.repository.DeviceRepository
import com.padosol.notification.repository.NotificationDeliveryRepository
import com.padosol.notification.repository.NotificationRequestRepository
import com.padosol.notification.repository.NotificationSettingRepository
import com.padosol.notification.repository.OutboxRepository
import com.padosol.notification.repository.TemplateRepository
import com.padosol.notification.service.NotificationService
import com.padosol.notification.service.OutboxRelay
import com.padosol.notification.service.SendCommand
import io.micrometer.core.instrument.MeterRegistry
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
import kotlin.test.assertEquals

/**
 * GOALS.md 라운드 D(피로도·보안) — D1~D5. 단일 채널(푸시).
 * 인증/인가는 HTTP(mockMvc), throttle/모니터링은 서비스+릴레이 직접 호출로 검증한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityThrottleIntegrationTest @Autowired constructor(
    val mockMvc: MockMvc,
    val users: AppUserRepository,
    val devices: DeviceRepository,
    val settings: NotificationSettingRepository,
    val templates: TemplateRepository,
    val requests: NotificationRequestRepository,
    val deliveries: NotificationDeliveryRepository,
    val outbox: OutboxRepository,
    val service: NotificationService,
    val relay: OutboxRelay,
    val push: PushProvider,
    val meterRegistry: MeterRegistry,
) {

    @AfterEach
    fun cleanup() {
        push.clear()
        outbox.deleteAll()
        deliveries.deleteAll()
        requests.deleteAll()
        settings.deleteAll()
        devices.deleteAll()
        templates.deleteAll()
        users.deleteAll()
    }

    @Test
    fun `D1 마케팅 한도 초과분은 throttle 드롭, transactional 은 면제`() {
        val user = seedUserWithDevice()
        templates.save(Template(templateId = "M", category = "marketing", body = "hi"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        (1..4).forEach { i -> service.accept(pushCmd(user, "M", "marketing", "m$i")) }
        drainRelay()

        assertEquals(3, push.count())
        assertEquals(1, deliveries.findAll().count { it.status == DeliveryStatus.THROTTLED })
        assertEquals(1, outbox.countByStatus(OutboxStatus.DROPPED))

        service.accept(pushCmd(user, "T", "transactional", "t1"))
        drainRelay()
        assertEquals(4, push.count())
    }

    @Test
    fun `D2 유효한 X-Api-Key 가 없으면 401`() {
        mockMvc.post("/v1/notifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"templateId":"T","category":"transactional"}"""
        }.andExpect { status { isUnauthorized() } }

        mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "bogus")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"templateId":"T","category":"transactional"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `D3 producer 권한 밖 category·priority 는 403`() {
        // mkt 키: 권한 = {categories: [marketing], maxPriority: bulk}
        mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "mkt-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"templateId":"T","category":"transactional"}"""
        }.andExpect { status { isForbidden() } }

        mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "mkt-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"templateId":"M","category":"marketing","priority":"high"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `D4 상태조회는 디바이스 토큰을 마스킹한다`() {
        val user = seedUserWithDevice(token = "tok-alice-123")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val requestId = postViaApi(user)

        mockMvc.get("/v1/notifications/$requestId") {
            header("X-Api-Key", "test-key")
        }.andExpect {
            status { isOk() }
            jsonPath("$.deliveries[0].target") { value("tok-***") }
        }
    }

    @Test
    fun `D5 backlog gauge 와 발송 카운터가 노출된다`() {
        val user = seedUserWithDevice()
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val sentBefore = sentCount()
        (1..2).forEach { i -> service.accept(pushCmd(user, "T", "transactional", "t$i")) }

        assertEquals(2.0, backlogGauge())

        drainRelay()

        assertEquals(0.0, backlogGauge())
        assertEquals(2.0, sentCount() - sentBefore)
    }

    // --- helpers ---

    private fun seedUserWithDevice(token: String = "tok"): Long {
        val user = users.save(AppUser()).id!!
        devices.save(Device(userId = user, token = token, platform = "ios"))
        return user
    }

    private fun pushCmd(userId: Long, templateId: String, category: String, dedupKey: String) = SendCommand(
        userId = userId,
        templateId = templateId,
        category = category,
        priority = null,
        params = emptyMap(),
        producerId = "default",
        dedupKey = dedupKey,
    )

    private fun drainRelay() {
        while (relay.publishPending(1000) > 0) { /* due 없을 때까지 */ }
    }

    private fun postViaApi(userId: Long): Long {
        val response = mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "test-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":$userId,"templateId":"T","category":"transactional"}"""
        }.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        return Regex(""""requestId"\s*:\s*(\d+)""").find(response)!!.groupValues[1].toLong()
    }

    private fun sentCount(): Double =
        meterRegistry.find("notification.sent").counter()?.count() ?: 0.0

    private fun backlogGauge(): Double =
        meterRegistry.find("notification.outbox.backlog").gauge()?.value() ?: 0.0
}
