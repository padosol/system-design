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
    val producerId: String = "default", // 호출 서비스 식별(라운드D에서 인증으로 대체). 멱등키 범위.
    val dedupKey: String? = null,        // (producerId, dedupKey) 단위 멱등(라운드B)
)

// --- 응답 ---

data class RegisterDeviceResponse(val deviceId: Long)

data class SendNotificationResponse(val requestId: Long)

data class RequestStatusResponse(val progress: String, val deliveries: List<DeliveryView>)
