package com.example.api

import com.example.auth.SessionAccessService
import com.example.chats.ChatDetailView
import com.example.chats.ChatReadService
import com.example.chats.ChatSummaryView
import com.example.chats.MembershipView
import com.example.chats.MessageView
import com.example.chats.ReadCursorResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
private data class ReadCursorInput(val lastReadMessageSeq: Long)

internal fun Route.chatReadRoutes(read: ChatReadService, sessions: SessionAccessService) {
    get("/api/v1/me/chats") {
        val actor = chatActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val limit = chatLimit(call.request.queryParameters["limit"])
        val page = try { read.myChats(actor, call.request.queryParameters["cursor"], limit) }
            catch (_: IllegalArgumentException) { throw invalidChatCursor() }
        call.respond(buildJsonObject {
            put("items", JsonArray(page.items.map(::chatSummaryJson)))
            put("nextCursor", page.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }
    get("/api/v1/chats/{chatId}") {
        val actor = chatActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val detail = read.detail(actor, chatId(call.parameters["chatId"])) ?: throw notMember()
        call.respond(chatDetailJson(detail))
    }
    get("/api/v1/chats/{chatId}/messages/search") {
        val actor = chatActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val id = chatId(call.parameters["chatId"])
        val q = call.request.queryParameters["q"]?.trim() ?: ""
        if (q.codePointCount(0, q.length) !in 3..256) throw ApiException(
            HttpStatusCode.BadRequest, "INVALID_FIELD", "Поиск: от 3 до 256 символов")
        val limit = chatLimit(call.request.queryParameters["limit"])
        val page = try { read.search(actor, id, q, call.request.queryParameters["cursor"], limit) }
            catch (_: IllegalArgumentException) { throw invalidChatCursor() }
            ?: throw notMember()
        call.respond(buildJsonObject {
            put("items", JsonArray(page.items.map(::messageJson)))
            put("nextCursor", page.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }
    get("/api/v1/chats/{chatId}/messages") {
        val actor = chatActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val id = chatId(call.parameters["chatId"])
        val params = call.request.queryParameters
        if (params["beforeSeq"] != null && params["afterSeq"] != null) throw invalidChatField()
        val before = params["beforeSeq"]?.toLongOrNull() ?: if (params["beforeSeq"] != null) throw invalidChatField() else null
        val after = params["afterSeq"]?.toLongOrNull() ?: if (params["afterSeq"] != null) throw invalidChatField() else null
        if ((before != null && before <= 0) || (after != null && after < 0)) throw invalidChatField()
        val limit = chatLimit(params["limit"])
        val page = read.history(actor, id, before, after, limit) ?: throw notMember()
        call.respond(buildJsonObject {
            put("items", JsonArray(page.items.map(::messageJson)))
            put("nextBeforeSeq", page.nextBeforeSeq?.let { JsonPrimitive(it) } ?: JsonNull)
            put("nextAfterSeq", page.nextAfterSeq?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }
    put("/api/v1/chats/{chatId}/read-cursor") {
        val actor = chatActor(sessions, call.request.headers[HttpHeaders.Authorization])
        val id = chatId(call.parameters["chatId"])
        val input = call.receiveStrictJson(ReadCursorInput.serializer())
        if (input.lastReadMessageSeq < 0) throw ApiException(
            HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректный курсор чтения")
        when (val result = read.advanceReadCursor(actor, id, input.lastReadMessageSeq)) {
            is ReadCursorResult.Updated -> call.respond(membershipJson(result.membership))
            ReadCursorResult.NotMember -> throw notMember()
            ReadCursorResult.BeyondLastMessage -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Курсор за пределами чата")
        }
    }
}

private suspend fun chatActor(sessions: SessionAccessService, authorization: String?): UUID =
    UUID.fromString(sessions.currentUser(authorization).id)

private fun chatId(raw: String?): UUID {
    val value = raw ?: throw invalidChatField()
    return try { UUID.fromString(value) } catch (_: IllegalArgumentException) { throw invalidChatField() }
}

private fun chatLimit(raw: String?): Int {
    if (raw == null) return 30
    return raw.toIntOrNull()?.takeIf { it in 1..100 } ?: throw invalidChatField()
}

private fun invalidChatField() = ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректные параметры")
private fun invalidChatCursor() = ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Некорректный курсор")
private fun notMember() = ApiException(HttpStatusCode.Forbidden, "NOT_MEMBER", "Нет доступа к чату")

private fun membershipJson(value: MembershipView): JsonObject = buildJsonObject {
    put("chatId", value.chatId.toString())
    put("userId", value.userId.toString())
    put("joinedAt", value.joinedAt.toString())
    put("lastReadMessageSeq", value.lastReadSeq)
}

private fun chatDetailJson(value: ChatDetailView): JsonObject = buildJsonObject {
    chatSummaryJson(value.summary).forEach { (key, element) -> put(key, element) }
    put("membership", membershipJson(value.membership))
    put("lastMessageSeq", value.lastMessageSeq)
}

private fun chatSummaryJson(value: ChatSummaryView): JsonObject = buildJsonObject {
    put("id", value.id.toString())
    put("pointId", value.pointId.toString())
    put("topic", buildJsonObject {
        put("id", value.topic.id.toString())
        put("presetKey", value.topic.presetKey)
        put("label", value.topic.label)
        put("participantCount", value.topic.participantCount)
        put("chatId", value.topic.chatId.toString())
    })
    put("address", value.address)
    put("participantCount", value.participantCount)
    put("lastMessage", value.lastMessage?.let { message -> buildJsonObject {
        put("id", message.id.toString())
        put("type", message.type)
        put("text", message.text)
        put("createdAt", message.createdAt.toString())
    } } ?: JsonNull)
    put("unreadCount", value.unreadCount)
    put("currentProposal", value.currentProposal?.let { proposal -> buildJsonObject {
        put("id", proposal.id.toString())
        put("startsAt", proposal.startsAt.toString())
        put("endsAt", proposal.endsAt.toString())
        put("status", proposal.status)
        put("goingCount", proposal.goingCount)
        put("bookingId", proposal.bookingId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("revision", proposal.revision)
    } } ?: JsonNull)
    put("updatedAt", value.updatedAt.toString())
}

internal fun messageJson(value: MessageView): JsonObject = buildJsonObject {
    put("id", value.id.toString())
    put("chatId", value.chatId.toString())
    put("messageSeq", value.messageSeq)
    put("type", value.type.lowercase())
    put("author", if (value.type == "TEXT") buildJsonObject {
        put("id", value.authorId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("displayName", value.authorName ?: "Удалённый пользователь")
        put("avatarUrl", value.authorId?.takeIf { value.authorAvatarObjectKey != null }
            ?.let { JsonPrimitive("/api/v1/users/$it/avatar") } ?: JsonNull)
    } else JsonNull)
    put("text", value.body?.let { JsonPrimitive(it) } ?: JsonNull)
    put("system", value.systemPayload ?: JsonNull)
    put("createdAt", value.createdAt.toString())
}
