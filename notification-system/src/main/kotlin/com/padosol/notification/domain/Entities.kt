package com.padosol.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 발송 채널. */
enum class Channel { PUSH, SMS, EMAIL }

/** 전달 상태. 라운드1은 QUEUED→SENT/SUPPRESSED, 라운드B에서 FAILED(DLQ) 추가 사용. */
enum class DeliveryStatus {
    QUEUED, SENT, DELIVERED, BOUNCED, EXPIRED, SUPPRESSED, FAILED;

    companion object {
        /** 더 이상 진행하지 않는 종료 상태 (진행도 집계용, §6-4). */
        val TERMINAL = setOf(SENT, DELIVERED, BOUNCED, EXPIRED, SUPPRESSED, FAILED)
    }
}

/** Outbox 발행 상태(라운드B). PENDING→PUBLISHED, 재시도 소진 시 DLQ. */
enum class OutboxStatus { PENDING, PUBLISHED, DLQ }

@Entity
@Table(name = "app_user")
class AppUser(
    @Column(name = "email") val email: String? = null,
    @Column(name = "phone") val phone: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "user_id") var id: Long? = null,
)

@Entity
@Table(name = "device")
class Device(
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(name = "token", nullable = false, unique = true, length = 512) val token: String,
    @Column(name = "platform", nullable = false, length = 16) val platform: String,
    @Column(name = "active", nullable = false) var active: Boolean = true,
    @Column(name = "last_seen", nullable = false) var lastSeen: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "device_id") var id: Long? = null,
)

@Entity
@Table(name = "notification_setting")
class NotificationSetting(
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Enumerated(EnumType.STRING) @Column(name = "channel", nullable = false, length = 16) val channel: Channel,
    @Column(name = "category", nullable = false, length = 32) val category: String,
    @Column(name = "enabled", nullable = false) var enabled: Boolean,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "setting_id") var id: Long? = null,
)

@Entity
@Table(name = "template")
class Template(
    @Id @Column(name = "template_id", length = 64) val templateId: String,
    @Column(name = "category", nullable = false, length = 32) val category: String,
    @Column(name = "body", nullable = false, length = 4000) val body: String,
    @Enumerated(EnumType.STRING) @Column(name = "channel", length = 16) val channel: Channel? = null,
    @Column(name = "priority", nullable = false, length = 16) val priority: String = "normal",
    @Column(name = "locale", length = 16) val locale: String? = null,
    @Column(name = "version", nullable = false) val version: Int = 1,
    @Column(name = "subject", length = 255) val subject: String? = null,
)

@Entity
@Table(name = "notification_request")
class NotificationRequest(
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(name = "category", nullable = false, length = 32) val category: String,
    @Column(name = "priority", nullable = false, length = 16) val priority: String,
    @Column(name = "producer_id", nullable = false, length = 64) val producerId: String = "default",
    @Column(name = "dedup_key", length = 128) val dedupKey: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "request_id") var id: Long? = null,
)

/** Transactional Outbox 행(라운드B). delivery 1건당 1행, request 와 같은 트랜잭션으로 커밋된다. */
@Entity
@Table(name = "outbox")
class Outbox(
    @Column(name = "delivery_id", nullable = false) val deliveryId: Long,
    @Enumerated(EnumType.STRING) @Column(name = "channel", nullable = false, length = 16) val channel: Channel,
    @Column(name = "target", nullable = false, length = 512) val target: String,
    @Column(name = "body", nullable = false, length = 4000) val body: String,
    @Column(name = "idempotency_key", nullable = false, length = 255) val idempotencyKey: String,
    @Column(name = "subject", length = 255) val subject: String? = null,
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16) var status: OutboxStatus = OutboxStatus.PENDING,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0,
    @Column(name = "next_attempt_at", nullable = false) var nextAttemptAt: Instant = Instant.now(),
    @Column(name = "published_at") var publishedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "outbox_id") var id: Long? = null,
)

@Entity
@Table(name = "notification_delivery")
class NotificationDelivery(
    @Column(name = "request_id", nullable = false) val requestId: Long,
    @Enumerated(EnumType.STRING) @Column(name = "channel", nullable = false, length = 16) val channel: Channel,
    @Column(name = "target", nullable = false, length = 512) val target: String,
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16) var status: DeliveryStatus,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "delivery_id") var id: Long? = null,
)
