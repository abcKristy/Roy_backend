package com.example.api

import com.example.auth.SessionAccessService
import com.example.points.AddTopicCommand
import com.example.points.AddTopicResult
import com.example.points.CreatePointCommand
import com.example.points.CreatePointResult
import com.example.points.JoinPointChatResult
import com.example.points.PointAddressSource
import com.example.points.PointMembership
import com.example.points.PointPosition
import com.example.points.PointTopicInput
import com.example.points.PointWriteService
import com.example.points.TopicPresetKeys
import com.example.realtime.RealtimeHub
import com.example.realtime.RealtimeEvent
import com.example.realtime.RealtimeEventType
import com.example.realtime.RealtimeScopeKind
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.withTimeoutOrNull
import com.example.points.LeavePointChatResult
import java.time.Instant
import java.util.UUID

@Serializable
private data class PositionInput(val lat: Double, val lon: Double)

@Serializable
private data class TopicInput(val presetKey: String, val customLabel: String? = null)

@Serializable
private data class CreatePointInput(
    val position: PositionInput, val topic: TopicInput, val address: String,
    val addressSource: String, val confirmNearby: Boolean,
)

@Serializable
private data class AddTopicInput(val topic: TopicInput)

@Serializable
private data class MembershipResponse(
    val chatId: String, val userId: String, val joinedAt: String,
    val lastReadMessageSeq: Long,
)

internal fun Route.pointWriteRoutes(write: PointWriteService, sessions: SessionAccessService, realtime: RealtimeHub? = null) {
    post("/api/v1/points") {
        val actor = actorId(sessions, call.request.headers[HttpHeaders.Authorization])
        val key = idempotencyKey(call.request.headers["Idempotency-Key"])
        val input = call.receiveStrictJson(CreatePointInput.serializer())
        validatePosition(input.position)
        validateAddress(input.address)
        val topic = validateTopic(input.topic)
        val source = when (input.addressSource) {
            "provider" -> PointAddressSource.GEOCODED
            "manual" -> PointAddressSource.MANUAL
            else -> throw invalidPointField()
        }
        val requestId = call.requestId()
        val result = write.create(
            CreatePointCommand(actor, key, PointPosition(input.position.lat, input.position.lon),
                topic, input.address, source, input.confirmNearby),
            PointWriteSnapshot::create,
        ) { blockedUntil ->
            buildJsonObject {
                put("error", buildJsonObject {
                    put("code", "POINT_QUOTA_EXCEEDED")
                    put("message", "Превышена квота создания точек")
                    put("details", buildJsonObject { put("blockedUntil", blockedUntil.toString()) })
                    put("retryAfterSeconds", kotlinx.serialization.json.JsonNull)
                })
                put("requestId", requestId)
            }
        }
        when (result) {
            is CreatePointResult.Created -> {
                call.publishMembership(realtime, result.value.chatId, actor, "joined")
                call.respondSnapshot(result.responseSnapshot)
            }
            is CreatePointResult.Replay -> call.respondSnapshot(result.responseSnapshot)
            is CreatePointResult.QuotaExceeded -> call.respondSnapshot(result.responseSnapshot)
            is CreatePointResult.NearbyConfirmationRequired -> throw ApiException(
                HttpStatusCode.Conflict, "NEARBY_CONFIRM_REQUIRED", "Рядом уже есть точки",
                buildJsonObject { put("nearby", JsonArray(result.nearby.map { nearby -> buildJsonObject {
                    put("id", nearby.id.toString())
                    put("position", buildJsonObject { put("lat", nearby.lat); put("lon", nearby.lon) })
                    put("address", nearby.address)
                    put("distanceMeters", nearby.distanceMeters)
                } })) },
            )
            is CreatePointResult.QuotaBlocked -> throw ApiException(
                HttpStatusCode.Conflict, "POINT_QUOTA_BLOCKED", "Создание точек временно недоступно",
                buildJsonObject { put("blockedUntil", result.blockedUntil.toString()) },
            )
            CreatePointResult.KeyReused -> throw keyReused()
        }
    }
    post("/api/v1/points/{pointId}/topics") {
        val actor = actorId(sessions, call.request.headers[HttpHeaders.Authorization])
        val key = idempotencyKey(call.request.headers["Idempotency-Key"])
        val pointId = parseId(call.parameters["pointId"])
        val input = call.receiveStrictJson(AddTopicInput.serializer())
        val result = write.addTopic(AddTopicCommand(actor, key, pointId, validateTopic(input.topic)),
            PointWriteSnapshot::addTopic)
        when (result) {
            is AddTopicResult.Created -> {
                call.publishMembership(realtime, result.value.chatId, actor, "joined")
                call.respondSnapshot(result.responseSnapshot)
            }
            is AddTopicResult.Replay -> call.respondSnapshot(result.responseSnapshot)
            AddTopicResult.KeyReused -> throw keyReused()
            AddTopicResult.PointGone -> throw ApiException(HttpStatusCode.NotFound, "RESOURCE_GONE", "Точка не найдена")
            AddTopicResult.DuplicateTopic -> throw ApiException(HttpStatusCode.Conflict, "DUPLICATE_TOPIC", "Такая тема уже есть")
        }
    }
    put("/api/v1/chats/{chatId}/membership") {
        val actor = actorId(sessions, call.request.headers[HttpHeaders.Authorization])
        when (val result = write.join(actor, parseId(call.parameters["chatId"]))) {
            is JoinPointChatResult.Joined -> {
                call.publishMembership(realtime, result.membership.chatId, actor, "joined")
                call.respond(result.membership.toResponse())
            }
            is JoinPointChatResult.AlreadyMember -> call.respond(result.membership.toResponse())
            JoinPointChatResult.ChatGone -> throw ApiException(HttpStatusCode.NotFound, "RESOURCE_GONE", "Чат не найден")
        }
    }
    delete("/api/v1/chats/{chatId}/membership") {
        val actor = actorId(sessions, call.request.headers[HttpHeaders.Authorization])
        val chatId = parseId(call.parameters["chatId"])
        when (val result = write.leave(actor, chatId)) {
            is LeavePointChatResult.Left -> {
                call.publishMembership(realtime, chatId, actor, "left")
                call.publishLeaveEffects(realtime, result, chatId)
                call.respond(HttpStatusCode.NoContent)
            }
            LeavePointChatResult.AlreadyLeft -> call.respond(HttpStatusCode.NoContent)
            LeavePointChatResult.ChatGone -> throw ApiException(
                HttpStatusCode.NotFound, "RESOURCE_GONE", "Чат не найден")
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.publishLeaveEffects(
    realtime: RealtimeHub?, result: LeavePointChatResult.Left, chatId: UUID,
) {
    if (realtime == null) return
    try {
        withTimeoutOrNull(1_500) {
            if (result.pointDeleted) {
                result.deletedRecipients.forEach { recipientId ->
                    realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.POINT_DELETED,
                        RealtimeScopeKind.USER, recipientId, Instant.now(), buildJsonObject {
                            put("pointId", result.pointId.toString()); put("reason", "no_members")
                        }))
                }
            } else {
                result.systemMessages.forEach { emission ->
                    val message = emission.asMessageView()
                    realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.CHAT_MESSAGE_CREATED,
                        RealtimeScopeKind.CHAT, chatId, message.createdAt, messageJson(message)))
                }
                result.changedProposals.forEach { proposal ->
                    realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.PROPOSAL_UPDATED,
                        RealtimeScopeKind.CHAT, chatId, proposal.updatedAt, buildJsonObject {
                            put("proposalId", proposal.id.toString())
                            put("revision", proposal.revision)
                            put("status", proposal.status)
                            put("goingCount", proposal.goingCount)
                            put("bookingId", proposal.booking?.id?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                        }))
                }
                result.bookingChanges.forEach { booking ->
                    realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.BOOKING_CHANGED,
                        RealtimeScopeKind.CHAT, chatId, Instant.now(), buildJsonObject {
                            put("proposalId", booking.proposalId.toString())
                            put("bookingId", booking.bookingId.toString())
                            put("status", booking.status)
                            put("cancelReason", booking.cancelReason)
                        }))
                }
            }
        }
    } catch (failure: Exception) {
        application.log.warn("Post-commit leave events failed for chat {}", chatId, failure)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.publishMembership(
    realtime: RealtimeHub?, chatId: UUID, userId: UUID, action: String,
) {
    if (realtime == null) return
    try {
        realtime.publishMembershipChanged(chatId, userId, action)
    } catch (failure: Exception) {
        application.log.warn("Post-commit WebSocket delivery failed for chat {}", chatId, failure)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSnapshot(snapshot: JsonObject) {
    val status = snapshot["status"]?.jsonPrimitive?.int ?: error("Missing saved HTTP status")
    val body = snapshot["body"] ?: error("Missing saved HTTP body")
    respond(HttpStatusCode.fromValue(status), body)
}

private fun PointMembership.toResponse() = MembershipResponse(
    chatId.toString(), userId.toString(), joinedAt.toString(), lastReadSeq,
)

private suspend fun actorId(sessions: SessionAccessService, authorization: String?): UUID =
    UUID.fromString(sessions.currentUser(authorization).id)

private fun idempotencyKey(raw: String?): UUID = parseId(raw)

private fun parseId(raw: String?): UUID {
    val value = raw ?: throw invalidIdentifier()
    return try { UUID.fromString(value) } catch (_: IllegalArgumentException) { throw invalidIdentifier() }
}

private fun invalidIdentifier() = ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный идентификатор")

private fun validatePosition(position: PositionInput) {
    if (!position.lat.isFinite() || !position.lon.isFinite() ||
        position.lat !in -90.0..90.0 || position.lon !in -180.0..180.0) throw invalidPointField()
}

private fun validateAddress(address: String) {
    val value = address.trim()
    if (value.codePointCount(0, value.length) !in 1..300) throw invalidPointField()
}

private fun validateTopic(topic: TopicInput): PointTopicInput {
    val label = topic.customLabel?.trim()
    if (topic.presetKey !in TopicPresetKeys.allowed ||
        (topic.presetKey == "other" && (label.isNullOrBlank() || label.codePointCount(0, label.length) > 40)) ||
        (topic.presetKey != "other" && label != null)) throw invalidPointField()
    return PointTopicInput(topic.presetKey, label)
}

private fun invalidPointField() = ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректные данные")

private fun keyReused() = ApiException(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED", "Ключ запроса уже использован")
