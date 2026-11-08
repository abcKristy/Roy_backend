package com.example.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class NotificationView(
    val id: UUID, val kind: String, val title: String, val body: String,
    val pointId: UUID?, val chatId: UUID?, val proposalId: UUID?,
    val createdAt: Instant, val readAt: Instant?,
)

internal fun readNotification(rows: ResultSet): NotificationView {
    val snapshot = Json.parseToJsonElement(rows.getString("display_snapshot")) as JsonObject
    val kind = rows.getString("event_type")
    val nearby = kind == "nearby_meeting"
    return NotificationView(
        rows.getObject("id", UUID::class.java), kind,
        (snapshot["title"] as? JsonPrimitive)?.content ?: "Уведомление",
        (snapshot["body"] as? JsonPrimitive)?.content ?: "",
        rows.getObject("point_id", UUID::class.java),
        if (nearby) null else rows.getObject("chat_id", UUID::class.java),
        if (nearby) null else rows.getObject("proposal_id", UUID::class.java),
        rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        rows.getObject("read_at", OffsetDateTime::class.java)?.toInstant(),
    )
}

internal fun notificationJson(value: NotificationView): JsonObject = buildJsonObject {
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
