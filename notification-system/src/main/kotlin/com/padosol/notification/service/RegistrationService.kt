package com.padosol.notification.service

import com.padosol.notification.domain.Channel
import com.padosol.notification.domain.Device
import com.padosol.notification.domain.NotificationSetting
import com.padosol.notification.repository.DeviceRepository
import com.padosol.notification.repository.NotificationSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 디바이스 토큰 등록/갱신, 수신 설정(opt-out) 관리. */
@Service
class RegistrationService(
    private val devices: DeviceRepository,
    private val settings: NotificationSettingRepository,
) {

    /** 같은 token 재등록은 upsert — 중복 행을 만들지 않는다(F1). */
    @Transactional
    fun registerDevice(userId: Long, token: String, platform: String): Device {
        val existing = devices.findByToken(token)
        return if (existing != null) {
            existing.active = true
            existing.lastSeen = Instant.now()
            devices.save(existing)
        } else {
            devices.save(Device(userId = userId, token = token, platform = platform))
        }
    }

    /** (user, channel, category) 단위 수신 설정 upsert(F2). */
    @Transactional
    fun updateSetting(userId: Long, channel: Channel, category: String, enabled: Boolean): NotificationSetting {
        val existing = settings.findByUserIdAndChannelAndCategory(userId, channel, category)
        return if (existing != null) {
            existing.enabled = enabled
            settings.save(existing)
        } else {
            settings.save(NotificationSetting(userId = userId, channel = channel, category = category, enabled = enabled))
        }
    }
}
