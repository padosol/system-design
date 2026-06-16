package com.padosol.notification.repository

import com.padosol.notification.domain.AppUser
import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.Device
import com.padosol.notification.domain.NotificationDelivery
import com.padosol.notification.domain.NotificationRequest
import com.padosol.notification.domain.NotificationSetting
import com.padosol.notification.domain.Template
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, Long>

interface DeviceRepository : JpaRepository<Device, Long> {
    fun findByToken(token: String): Device?
    fun findByUserIdAndActiveTrue(userId: Long): List<Device>
}

interface NotificationSettingRepository : JpaRepository<NotificationSetting, Long> {
    fun findByUserIdAndChannelAndCategory(userId: Long, channel: Channel, category: String): NotificationSetting?
}

interface TemplateRepository : JpaRepository<Template, String>

interface NotificationRequestRepository : JpaRepository<NotificationRequest, Long>

interface NotificationDeliveryRepository : JpaRepository<NotificationDelivery, Long> {
    fun findByRequestId(requestId: Long): List<NotificationDelivery>
}
