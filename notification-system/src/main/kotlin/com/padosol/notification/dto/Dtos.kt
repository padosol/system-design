package com.padosol.notification.dto

import com.padosol.notification.domain.Channel
import com.padosol.notification.service.DeliveryView
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

// --- 요청 ---

data class RegisterDeviceRequest(
    @field:NotNull val userId: Long,
    @field:NotBlank val token: String,
    @field:NotBlank val platform: String,
)

data class UpdateSettingRequest(
    @field:NotNull val channel: Channel,
    @field:NotBlank val category: String,
    @field:NotNull val enabled: Boolean,
)

data class SendNotificationRequest(
    @field:NotNull val userId: Long,
    val channel: Channel? = null,
    @field:NotBlank val templateId: String,
    @field:NotBlank val category: String,
    val priority: String? = null,
    val params: Map<String, String> = emptyMap(),
    val dedupKey: String? = null, // 라운드B(멱등)에서 사용. 라운드1은 접수만.
)

// --- 응답 ---

data class RegisterDeviceResponse(val deviceId: Long)

data class SendNotificationResponse(val requestId: Long)

data class RequestStatusResponse(val progress: String, val deliveries: List<DeliveryView>)
