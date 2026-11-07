package com.example.api

import com.example.auth.SessionAccessService
import com.example.notifications.LocationWriteResult
import com.example.notifications.NotificationLocationView
import com.example.notifications.NotificationService
import com.example.notifications.NotificationSettingsChange
import com.example.notifications.NotificationSettingsView
import com.example.notifications.NotificationView
import com.example.notifications.SettingsUpdateResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
private data class NotificationSettingsInput(
    val remindersPushEnabled: Boolean, val personalPushEnabled: Boolean,
    val extraPushEnabled: Boolean, val extraInAppEnabled: Boolean, val expectedRevision: Long,
)

private object NotificationSettingsInputSerializer :
    JsonTransformingSerializer<NotificationSettingsInput>(NotificationSettingsInput.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        // The standard decoder also coerces quoted strings to Booleans and Longs.
        if ((element as? JsonObject)?.values?.any { it is JsonPrimitive && it.isString } == true) {
            throw SerializationException("Expected JSON booleans and an integer revision")
        }
        return element
    }
}

@Serializable private data class LocationPositionInput(val lat: Double, val lon: Double)
@Serializable private data class NotificationLocationInput(val position: LocationPositionInput)

internal fun Route.notificationRoutes(service: NotificationService, sessions: SessionAccessService) {
    get("/api/v1/me/notification-settings") {
        val actor = notificationActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val settings = service.settings(actor) ?: throw notificationUnavailable()
        call.respond(settingsJson(settings))
    }
    put("/api/v1/me/notification-settings") {
        val actor = notificationActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val input = call.receiveStrictJson(NotificationSettingsInputSerializer)
        if (input.expectedRevision < 0) throw invalidNotificationField()
        when (val result = service.updateSettings(actor, NotificationSettingsChange(
            input.remindersPushEnabled, input.personalPushEnabled,
            input.extraPushEnabled, input.extraInAppEnabled, input.expectedRevision,
        ))) {
            is SettingsUpdateResult.Updated -> call.respond(settingsJson(result.settings))
            is SettingsUpdateResult.Stale -> throw ApiException(
                HttpStatusCode.Conflict, "STALE_STATE", "Настройки изменились. Обновите их перед сохранением",
                details = settingsJson(result.settings),
            )
            SettingsUpdateResult.Unavailable -> throw notificationUnavailable()
        }
    }
    get("/api/v1/me/notifications") {
        val actor = notificationActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val limit = notificationLimit(call.request.queryParameters["limit"])
        val page = try { service.list(actor, call.request.queryParameters["cursor"], limit) }
            catch (_: IllegalArgumentException) { throw ApiException(
                HttpStatusCode.BadRequest, "INVALID_CURSOR", "Некорректный курсор") }
        call.respond(buildJsonObject {
            put("items", JsonArray(page.items.map(::notificationJson)))
            put("nextCursor", page.nextCursor?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
    put("/api/v1/me/notifications/{notificationId}/read") {
        val actor = notificationActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val id = notificationId(call.parameters["notificationId"])
        val view = service.markRead(actor, id) ?: throw ApiException(
            HttpStatusCode.NotFound, "RESOURCE_GONE", "Уведомление не найдено")
        call.respond(notificationJson(view))
    }
    get("/api/v1/me/notification-location") {
        val actor = notificationActor(sessions, call.request.headers[HttpHeaders.Authorization])
        call.respond(locationJson(service.location(actor)))
    }
    put("/api/v1/me/notification-location") {
        val actor = notificationActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val input = call.receiveStrictJson(NotificationLocationInput.serializer())
        when (val result = service.setLocation(actor, input.position.lat, input.position.lon)) {
            is LocationWriteResult.Updated -> call.respond(locationJson(result.location))
            LocationWriteResult.InvalidPosition, LocationWriteResult.DisabledBySettings -> throw invalidNotificationField()
            LocationWriteResult.PolicyUnavailable -> throw notificationUnavailable()
            LocationWriteResult.PolicyNotAccepted -> throw ApiException(
                HttpStatusCode.Forbidden, "POLICY_NOT_ACCEPTED", "Примите актуальную политику конфиденциальности")
            LocationWriteResult.AccountUnavailable -> throw ApiException(
                HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "Сеанс истёк. Войдите снова")
        }
    }
    delete("/api/v1/me/notification-location") {
        val actor = notificationActor(sessions, call.request.headers[HttpHeaders.Authorization])
        service.deleteLocation(actor)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun settingsJson(value: NotificationSettingsView): JsonObject = buildJsonObject {
    put("remindersPushEnabled", value.remindersPushEnabled)
    put("personalPushEnabled", value.personalPushEnabled)
    put("extraPushEnabled", value.extraPushEnabled)
    put("extraInAppEnabled", value.extraInAppEnabled)
    put("revision", value.revision)
}

private fun notificationJson(value: NotificationView): JsonObject = buildJsonObject {
    put("id", value.id.toString())
    put("kind", value.kind)
    put("title", value.title)
    put("body", value.body)
    put("target", buildJsonObject {
        put("pointId", value.pointId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("chatId", value.chatId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("proposalId", value.proposalId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    })
    put("createdAt", value.createdAt.toString())
    put("readAt", value.readAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
}

private fun locationJson(value: NotificationLocationView): JsonObject = buildJsonObject {
    put("position", if (value.latitude != null && value.longitude != null) buildJsonObject {
        put("lat", value.latitude)
        put("lon", value.longitude)
    } else JsonNull)
    put("updatedAt", value.updatedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
}

private suspend fun notificationActor(sessions: SessionAccessService, authorization: String?): UUID =
    UUID.fromString(sessions.currentUser(authorization).id)

private fun notificationId(raw: String?): UUID = try {
    UUID.fromString(raw ?: throw IllegalArgumentException())
} catch (_: IllegalArgumentException) {
    throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный идентификатор")
}

private fun notificationLimit(raw: String?): Int = if (raw == null) 30 else
    raw.toIntOrNull()?.takeIf { it in 1..100 } ?: throw ApiException(
        HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный размер страницы")

private fun invalidNotificationField() = ApiException(
    HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректные данные уведомлений")

private fun notificationUnavailable() = ApiException(
    HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Функция временно недоступна")
