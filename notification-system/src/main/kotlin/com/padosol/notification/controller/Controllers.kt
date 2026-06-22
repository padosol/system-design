package com.padosol.notification.controller

import com.padosol.notification.dto.RegisterDeviceRequest
import com.padosol.notification.dto.RegisterDeviceResponse
import com.padosol.notification.dto.RequestStatusResponse
import com.padosol.notification.dto.SendNotificationRequest
import com.padosol.notification.dto.SendNotificationResponse
import com.padosol.notification.dto.UpdateSettingRequest
import com.padosol.notification.service.NotificationService
import com.padosol.notification.service.OutboxRelay
import com.padosol.notification.service.RegistrationService
import com.padosol.notification.service.SendCommand
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class DeviceController(private val registration: RegistrationService) {

    @PostMapping("/v1/devices")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterDeviceRequest): RegisterDeviceResponse {
        val device = registration.registerDevice(request.userId, request.token, request.platform)
        return RegisterDeviceResponse(deviceId = device.id!!)
    }
}

@RestController
class SettingController(private val registration: RegistrationService) {

    @PutMapping("/v1/users/{userId}/settings")
    fun update(@PathVariable userId: Long, @Valid @RequestBody request: UpdateSettingRequest) {
        registration.updateSetting(userId, request.channel, request.category, request.enabled)
    }
}

@RestController
class NotificationController(
    private val notifications: NotificationService,
    private val relay: OutboxRelay,
) {

    @PostMapping("/v1/notifications")
    @ResponseStatus(HttpStatus.ACCEPTED) // 202 — 접수만 보장(설계 §3)
    fun send(@Valid @RequestBody request: SendNotificationRequest): SendNotificationResponse {
        val result = notifications.accept(
            SendCommand(
                userId = request.userId,
                channel = request.channel,
                templateId = request.templateId,
                category = request.category,
                priority = request.priority,
                params = request.params,
                producerId = request.producerId,
                dedupKey = request.dedupKey,
            ),
        )
        // 저지연 인라인 발행(설계 §6-1). 실패해도 outbox 가 남아 릴레이가 회수하므로 유실되지 않는다.
        relay.publishPending()
        return SendNotificationResponse(requestId = result.requestId)
    }

    @GetMapping("/v1/notifications/{requestId}")
    fun status(@PathVariable requestId: Long): RequestStatusResponse {
        val status = notifications.status(requestId)
        return RequestStatusResponse(progress = status.progress, deliveries = status.deliveries)
    }
}
