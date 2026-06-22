package com.padosol.notification

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.OutboxStatus
import com.padosol.notification.domain.Template
import com.padosol.notification.provider.RecordingProviders
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
 * GOALS.md 라운드 D(피로도·보안) — D1~D5.
 * 인증/인가는 HTTP(mockMvc) 경로로, throttle/모니터링은 서비스+릴레이 직접 호출로 검증한다.
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
    val recorder: RecordingProviders,
    val meterRegistry: MeterRegistry,
) {

    @AfterEach
    fun cleanup() {
        recorder.clear()
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
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "M", category = "marketing", body = "hi"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        // 마케팅 4건 (한도 3) → 3 발송, 1 드롭
        (1..4).forEach { i -> service.accept(emailCmd(user, "M", "marketing", "m$i")) }
        drainRelay()

        assertEquals(3, recorder.countByChannel(Channel.EMAIL))
        assertEquals(1, deliveries.findAll().count { it.status == DeliveryStatus.THROTTLED })
        assertEquals(1, outbox.countByStatus(OutboxStatus.DROPPED))

        // transactional 은 한도 면제 — 항상 발송
        service.accept(emailCmd(user, "T", "transactional", "t1"))
        drainRelay()
        assertEquals(4, recorder.countByChannel(Channel.EMAIL))
    }

    @Test
    fun `D2 유효한 X-Api-Key 가 없으면 401`() {
        mockMvc.post("/v1/notifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"channel":"EMAIL","templateId":"T","category":"transactional"}"""
        }.andExpect { status { isUnauthorized() } }

        mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "bogus")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"channel":"EMAIL","templateId":"T","category":"transactional"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `D3 producer 권한 밖 category·priority 는 403`() {
        // mkt 키: 권한 = {categories: [marketing], maxPriority: bulk}
        // 권한 밖 category(transactional)
        mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "mkt-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"channel":"EMAIL","templateId":"T","category":"transactional"}"""
        }.andExpect { status { isForbidden() } }

        // 허용 category 라도 권한 밖 priority(high > bulk)
        mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "mkt-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":1,"channel":"EMAIL","templateId":"M","category":"marketing","priority":"high"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `D4 상태조회는 연락처를 마스킹한다`() {
        val user = seedUser(email = "alice@example.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val requestId = postViaApi(user)

        mockMvc.get("/v1/notifications/$requestId") {
            header("X-Api-Key", "test-key")
        }.andExpect {
            status { isOk() }
            jsonPath("$.deliveries[0].target") { value("a***@example.com") }
        }
    }

    @Test
    fun `D5 backlog gauge 와 채널별 발송 카운터가 노출된다`() {
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))

        val sentBefore = sentCount("EMAIL")
        (1..2).forEach { i -> service.accept(emailCmd(user, "T", "transactional", "t$i")) }

        assertEquals(2.0, backlogGauge()) // 미발행 outbox 2

        drainRelay()

        assertEquals(0.0, backlogGauge())
        assertEquals(2.0, sentCount("EMAIL") - sentBefore)
    }

    // --- helpers ---

    private fun seedUser(email: String? = null): Long = users.save(AppUser(email = email)).id!!

    private fun emailCmd(userId: Long, templateId: String, category: String, dedupKey: String) = SendCommand(
        userId = userId,
        channel = Channel.EMAIL,
        templateId = templateId,
        category = category,
        priority = null,
        params = emptyMap(),
        producerId = "default",
        dedupKey = dedupKey,
    )

    private fun drainRelay() {
        while (relay.publishPending(1000) > 0) { /* 남은 due 없을 때까지 */ }
    }

    private fun postViaApi(userId: Long): Long {
        val response = mockMvc.post("/v1/notifications") {
            header("X-Api-Key", "test-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId":$userId,"channel":"EMAIL","templateId":"T","category":"transactional"}"""
        }.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        return Regex(""""requestId"\s*:\s*(\d+)""").find(response)!!.groupValues[1].toLong()
    }

    private fun sentCount(channel: String): Double =
        meterRegistry.find("notification.sent").tag("channel", channel).counter()?.count() ?: 0.0

    private fun backlogGauge(): Double =
        meterRegistry.find("notification.outbox.backlog").gauge()?.value() ?: 0.0
}
