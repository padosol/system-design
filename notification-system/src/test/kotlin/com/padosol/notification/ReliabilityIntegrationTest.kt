package com.padosol.notification

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.DeliveryStatus
import com.padosol.notification.domain.Device
import com.padosol.notification.domain.OutboxStatus
import com.padosol.notification.domain.Template
import com.padosol.notification.provider.RecordingProviders
import com.padosol.notification.repository.AppUserRepository
import com.padosol.notification.repository.NotificationDeliveryRepository
import com.padosol.notification.repository.NotificationRequestRepository
import com.padosol.notification.repository.NotificationSettingRepository
import com.padosol.notification.repository.OutboxRepository
import com.padosol.notification.repository.TemplateRepository
import com.padosol.notification.repository.DeviceRepository
import com.padosol.notification.service.NotificationService
import com.padosol.notification.service.OutboxRelay
import com.padosol.notification.service.SendCommand
import com.padosol.notification.service.ThrottleStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GOALS.md 라운드 B(신뢰성) — B1~B5 정량 완료 조건.
 * base-backoff=0 으로 재시도를 즉시 due 로 만들어 루프를 빠르게 돌린다(백오프 지수성은 BackoffTest 가 검증).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["notification.outbox.base-backoff-millis=0", "notification.outbox.max-retry=5"])
class ReliabilityIntegrationTest @Autowired constructor(
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
    val throttleStore: ThrottleStore,
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
    fun `B1 같은 (producerId,dedupKey) 재접수는 1건으로 흡수되고 실발송 1회`() {
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        val cmd = emailCmd(user, dedupKey = "k1")

        val r1 = service.accept(cmd)
        val r2 = service.accept(cmd)

        assertEquals(r1.requestId, r2.requestId)
        assertFalse(r1.deduplicated)
        assertTrue(r2.deduplicated)
        assertEquals(1, requests.count())

        relay.publishPending()
        assertEquals(1, recorder.sent.size)
    }

    @Test
    fun `B1 동시 접수 50건도 정확히 1건만 영속`() {
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        val cmd = emailCmd(user, dedupKey = "kc")

        val pool = Executors.newFixedThreadPool(8)
        try {
            val ids = (1..50).map { pool.submit(Callable { service.accept(cmd).requestId }) }.map { it.get() }
            assertEquals(1, ids.toSet().size, "모든 동시 호출이 같은 requestId 를 받아야 한다")
        } finally {
            pool.shutdown()
        }
        assertEquals(1, requests.count())
    }

    @Test
    fun `B2 인라인 발행 없이 접수된 outbox 를 릴레이가 유실 없이 회수`() {
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        val n = 50
        (1..n).forEach { i -> service.accept(emailCmd(user, dedupKey = "k$i")) }

        assertEquals(n.toLong(), outbox.count())
        assertEquals(0, recorder.sent.size) // 아직 발행 전

        var total = 0
        while (true) {
            val c = relay.publishPending(1000)
            total += c
            if (c == 0) break
        }
        assertEquals(n, recorder.sent.size) // 유실 0
        assertEquals(n.toLong(), outbox.countByStatus(OutboxStatus.PUBLISHED))
    }

    @Test
    fun `B3 발행 실패 시 outbox 는 PENDING 으로 잔존하고 회복되면 회수된다`() {
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        recorder.failingChannels.add(Channel.EMAIL)
        service.accept(emailCmd(user, dedupKey = "k1"))

        relay.publishPending()

        val pending = outbox.findAll().single()
        assertEquals(OutboxStatus.PENDING, pending.status) // 미발행 잔존
        assertEquals(1, pending.attemptCount)
        assertEquals(0, recorder.sent.size)
        // 원자성: request·delivery·outbox 가 함께 존재
        assertEquals(1, requests.count())
        assertEquals(1, deliveries.count())

        recorder.failingChannels.clear() // provider 회복
        relay.publishPending()

        assertEquals(OutboxStatus.PUBLISHED, outbox.findAll().single().status)
        assertEquals(1, recorder.sent.size)
    }

    @Test
    fun `B4 영속 실패는 maxRetry(5) 후 DLQ 로 격리되고 delivery FAILED`() {
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        recorder.failingChannels.add(Channel.EMAIL)
        service.accept(emailCmd(user, dedupKey = "k1"))

        repeat(6) { relay.publishPending() } // base-backoff=0 이라 매번 due

        val o = outbox.findAll().single()
        assertEquals(OutboxStatus.DLQ, o.status)
        assertEquals(5, o.attemptCount)
        assertEquals(DeliveryStatus.FAILED, deliveries.findAll().single().status)
        assertEquals(0, recorder.sent.size)
    }

    @Test
    fun `B5 크래시로 재발행해도 provider 멱등으로 실발송은 1회`() {
        val user = seedUser(email = "a@b.com")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        service.accept(emailCmd(user, dedupKey = "k1"))

        relay.publishPending()
        assertEquals(1, recorder.sent.size)

        // 크래시 재현: 발송됐지만 PUBLISHED 커밋 전 죽음 → PENDING 으로 되돌려 재발행
        val o = outbox.findAll().single()
        o.status = OutboxStatus.PENDING
        o.nextAttemptAt = Instant.now()
        outbox.save(o)

        relay.publishPending()
        assertEquals(1, recorder.sent.size) // 같은 idempotencyKey → 중복 발송 0
    }

    @Test
    fun `accept 시 user 가 없으면 404`() {
        assertFailsWith<ResponseStatusException> {
            service.accept(emailCmd(userId = 999_999, dedupKey = null))
        }
    }

    @Test
    fun `accept 시 template 이 없으면 404`() {
        val user = seedUser(email = "a@b.com")
        assertFailsWith<ResponseStatusException> {
            service.accept(SendCommand(user, Channel.EMAIL, "NOPE", "transactional", null, emptyMap(), "p", null))
        }
    }

    @Test
    fun `status 는 미발행이면 pending, 일부만 종료되면 partial`() {
        val user = seedUser(email = "a@b.com")
        devices.save(Device(userId = user, token = "tok", platform = "ios"))
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        // channel 미지정 → push + email = delivery 2건 (모두 QUEUED)
        val requestId = service.accept(
            SendCommand(user, null, "T", "transactional", null, emptyMap(), "p", "k1"),
        ).requestId

        assertEquals("pending", service.status(requestId).progress)

        val first = deliveries.findByRequestId(requestId).first()
        first.status = DeliveryStatus.SENT
        deliveries.save(first)

        assertEquals("partial", service.status(requestId).progress)
    }

    @Test
    fun `SMS 채널도 발송된다`() {
        val user = seedUser(phone = "+15551234")
        templates.save(Template(templateId = "T", category = "transactional", body = "hi"))
        service.accept(SendCommand(user, Channel.SMS, "T", "transactional", null, emptyMap(), "p", "sms1"))

        relay.publishPending()
        assertEquals(1, recorder.countByChannel(Channel.SMS))
    }

    @Test
    fun `throttle 재시도는 같은 토큰이면 idempotent 하게 허용`() {
        assertTrue(throttleStore.tryAcquire(1L, "covtest", "tok-x"))
        assertTrue(throttleStore.tryAcquire(1L, "covtest", "tok-x")) // 같은 토큰 → 카운트 증가 없이 허용
    }

    // --- helpers ---

    private fun seedUser(email: String? = null, phone: String? = null): Long =
        users.save(AppUser(email = email, phone = phone)).id!!

    private fun emailCmd(userId: Long, dedupKey: String?) = SendCommand(
        userId = userId,
        channel = Channel.EMAIL,
        templateId = "T",
        category = "transactional",
        priority = null,
        params = emptyMap(),
        producerId = "p1",
        dedupKey = dedupKey,
    )
}
