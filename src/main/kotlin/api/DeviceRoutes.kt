package com.example.api

import com.example.auth.SessionAccessService
import com.example.devices.DeviceService
import com.example.devices.RegisterDeviceCommand
import com.example.devices.RegisterDeviceResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class PushDeviceInput(val platform: String, val provider: String, val token: String)

@Serializable
private data class PushDeviceResponse(val installationId: String, val registeredAt: String)

internal fun Route.deviceRoutes(service: DeviceService, sessions: SessionAccessService) {
    put("/api/v1/me/push-devices/{installationId}") {
        val userId = UUID.fromString(sessions.currentUser(call.request.headers[HttpHeaders.Authorization]).id)
        val installationId = deviceId(call.parameters["installationId"])
        val input = call.receiveStrictJson(PushDeviceInput.serializer())
        val platform = when (input.platform to input.provider) {
            "android" to "rustore" -> "ANDROID_RUSTORE"
            "ios" to "apns" -> "IOS_APNS"
            else -> throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректная платформа устройства")
        }
        when (val result = service.register(RegisterDeviceCommand(userId, installationId, platform, input.token))) {
            is RegisterDeviceResult.Registered -> call.respond(PushDeviceResponse(
                result.value.installationId.toString(), result.value.registeredAt.toString()))
            RegisterDeviceResult.InvalidToken -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректный токен устройства")
            RegisterDeviceResult.TokenInUse -> throw ApiException(
                HttpStatusCode.Conflict, "DEVICE_TOKEN_IN_USE", "Токен уже привязан к установке")
            RegisterDeviceResult.UserUnavailable -> throw ApiException(
                HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "Сеанс истёк. Войдите снова")
        }
    }
    delete("/api/v1/me/push-devices/{installationId}") {
        val userId = UUID.fromString(sessions.currentUser(call.request.headers[HttpHeaders.Authorization]).id)
        service.revoke(userId, deviceId(call.parameters["installationId"]))
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun deviceId(value: String?): UUID = try {
    UUID.fromString(value ?: throw IllegalArgumentException())
} catch (_: IllegalArgumentException) {
    throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный идентификатор установки")
}
