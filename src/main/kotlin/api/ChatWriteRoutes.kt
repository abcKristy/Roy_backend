package com.example.api

import com.example.auth.SessionAccessService
import com.example.chats.ChatSendService
import com.example.chats.SendMessageCommand
import com.example.chats.SendMessageResult
import com.example.realtime.RealtimeEvent
import com.example.realtime.RealtimeEventType
import com.example.realtime.RealtimeHub
import com.example.realtime.RealtimeScopeKind
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
private data class SendMessageInput(val text: String)

internal fun Route.chatWriteRoutes(
    write: ChatSendService,
    sessions: SessionAccessService,
    realtime: RealtimeHub? = null,
) {
    post("/api/v1/chats/{chatId}/messages") {
        val actor = UUID.fromString(sessions.currentUser(call.request.headers[HttpHeaders.Authorization]).id)
        val chatId = requiredUuid(call.parameters["chatId"])
        val key = requiredUuid(call.request.headers["Idempotency-Key"])
        val input = call.receiveStrictJson(SendMessageInput.serializer())
        when (val result = write.send(SendMessageCommand(actor, chatId, key, input.text)) { _, message ->
            messageJson(message)
        }) {
            is SendMessageResult.Created -> {
                if (realtime != null) {
                    try {
                        realtime.publishCommitted(RealtimeEvent(
                            UUID.randomUUID(), RealtimeEventType.CHAT_MESSAGE_CREATED,
                            RealtimeScopeKind.CHAT, chatId, result.message.createdAt,
                            messageJson(result.message),
                        ))
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        call.application.log.warn("Post-commit WebSocket delivery failed for chat {}", chatId, failure)
                    }
                }
                call.respondMessageSnapshot(result.responseSnapshot)
            }
            is SendMessageResult.Replay -> call.respondMessageSnapshot(result.responseSnapshot)
            SendMessageResult.KeyReused -> throw ApiException(
                HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED", "Ключ запроса уже использован")
            SendMessageResult.NotMember -> throw ApiException(
                HttpStatusCode.Forbidden, "NOT_MEMBER", "Нет доступа к чату")
            is SendMessageResult.Muted -> throw ApiException(
                HttpStatusCode.Forbidden, "MESSAGE_MUTED", "Отправка сообщений временно недоступна",
                buildJsonObject { put("mutedUntil", result.until.toString()) })
            is SendMessageResult.Flood -> {
                call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                throw ApiException(HttpStatusCode.TooManyRequests, "MESSAGE_FLOOD", "Слишком частые сообщения",
                    retryAfterSeconds = result.retryAfterSeconds)
            }
            SendMessageResult.RejectedPattern -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, "MESSAGE_REJECTED_PATTERN", "Сообщение нарушает правила",
                buildJsonObject { put("category", "phone_or_url_or_profanity") })
            SendMessageResult.InvalidText -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректный текст сообщения")
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondMessageSnapshot(snapshot: JsonObject) {
    val status = snapshot["status"]?.jsonPrimitive?.int ?: error("Saved message has no HTTP status")
    val body = snapshot["body"] ?: error("Saved message has no HTTP body")
    respond(HttpStatusCode.fromValue(status), body)
}

private fun requiredUuid(raw: String?): UUID {
    val value = raw ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный идентификатор")
    return try { UUID.fromString(value) } catch (_: IllegalArgumentException) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный идентификатор")
    }
}
