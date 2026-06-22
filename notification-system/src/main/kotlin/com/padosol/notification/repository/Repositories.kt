package com.padosol.notification.repository

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Device
import com.padosol.notification.domain.NotificationDelivery
import com.padosol.notification.domain.NotificationRequest
import com.padosol.notification.domain.NotificationSetting
import com.padosol.notification.domain.Outbox
import com.padosol.notification.domain.OutboxStatus
import com.padosol.notification.domain.Template
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface AppUserRepository : JpaRepository<AppUser, Long>

interface DeviceRepository : JpaRepository<Device, Long> {
    fun findByToken(token: String): Device?
    fun findByUserIdAndActiveTrue(userId: Long): List<Device>
}

interface NotificationSettingRepository : JpaRepository<NotificationSetting, Long> {
    fun findByUserIdAndCategory(userId: Long, category: String): NotificationSetting?
}

interface TemplateRepository : JpaRepository<Template, String>

interface NotificationRequestRepository : JpaRepository<NotificationRequest, Long> {
    fun findByProducerIdAndDedupKey(producerId: String, dedupKey: String): NotificationRequest?
}

interface NotificationDeliveryRepository : JpaRepository<NotificationDelivery, Long> {
    fun findByRequestId(requestId: Long): List<NotificationDelivery>
}

interface OutboxRepository : JpaRepository<Outbox, Long> {
    /** 발행 대상: 해당 상태이고 next_attempt_at 이 지난(due) 행을 오래된 순으로. */
    fun findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
        status: OutboxStatus,
        threshold: Instant,
        pageable: Pageable,
    ): List<Outbox>

    fun findByStatus(status: OutboxStatus): List<Outbox>
    fun countByStatus(status: OutboxStatus): Long
}
