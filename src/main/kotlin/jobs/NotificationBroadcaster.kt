package com.example.jobs

import com.example.persistence.DatabaseRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Visible in-app events are read after commit. Duplicate WS sends are allowed; REST is authoritative. */
class NotificationBroadcaster(
    private val database: DatabaseRuntime,
    private val publish: suspend (recipientId: UUID, eventId: UUID, payload: JsonObject, createdAt: Instant) -> Unit,
    private val lookbackMinutes: Int = 10,
    private val rememberedIds: Int = 20_000,
) {
    init { require(lookbackMinutes in 1..60); require(rememberedIds in 100..100_000) }

    private val seen = LinkedHashMap<UUID, Unit>()
    private var after: Position? = null

    /** Call from one runtime loop; the callback runs outside the read transaction. */
    suspend fun tick(maxEvents: Int = 5_000): Int {
        require(maxEvents in 1..20_000)
        val cursor = after
        val events = database.withReadOnlyJdbcTransaction { connection ->
            val afterFilter = if (cursor == null) "" else "AND (created_at,id)>(?,?)"
            connection.prepareStatement(
                """SELECT id,recipient_user_id,event_type,point_id,chat_id,proposal_id,
                          display_snapshot,created_at,read_at
                   FROM notification_events
                   WHERE visible_in_app=true
                     AND created_at>=statement_timestamp()-(? * interval '1 minute')
                     $afterFilter
                   ORDER BY created_at,id LIMIT ?"""
            ).use { statement ->
                statement.setInt(1, lookbackMinutes)
                if (cursor == null) {
                    statement.setInt(2, maxEvents)
                } else {
                    statement.setObject(2, cursor.createdAt.atOffset(ZoneOffset.UTC))
                    statement.setObject(3, cursor.id)
                    statement.setInt(4, maxEvents)
                }
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(VisibleEvent(
                        rows.getObject("id", UUID::class.java),
                        rows.getObject("recipient_user_id", UUID::class.java),
                        rows.getString("event_type"),
                        rows.getObject("point_id", UUID::class.java),
                        rows.getObject("chat_id", UUID::class.java),
                        rows.getObject("proposal_id", UUID::class.java),
                        Json.parseToJsonElement(rows.getString("display_snapshot")) as JsonObject,
                        rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                        rows.getObject("read_at", OffsetDateTime::class.java)?.toInstant(),
                    ))
                } }
            }
        }
        var count = 0
        for (event in events) {
            if (event.id in seen) continue
            try {
                publish(event.recipientId, event.id, event.publicPayload(), event.createdAt)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                continue // Retry when the rolling window is scanned again.
            }
            seen[event.id] = Unit
            count++
            while (seen.size > rememberedIds) seen.remove(seen.keys.first())
        }
        // Restart at the end to catch rows committed after we passed their insertion timestamp.
        after = if (events.size == maxEvents) events.last().let { Position(it.createdAt, it.id) } else null
        return count
    }

    private data class Position(val createdAt: Instant, val id: UUID)

    private data class VisibleEvent(
        val id: UUID, val recipientId: UUID, val kind: String, val pointId: UUID?,
        val chatId: UUID?, val proposalId: UUID?, val snapshot: JsonObject,
        val createdAt: Instant, val readAt: Instant?,
    ) {
        fun publicPayload(): JsonObject = buildJsonObject {
            put("id", id.toString())
            put("kind", kind)
            put("title", snapshot["title"]?.jsonPrimitive?.content ?: "Уведомление")
            put("body", snapshot["body"]?.jsonPrimitive?.content ?: "Откройте приложение")
            put("target", buildJsonObject {
                put("pointId", pointId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("chatId", if (kind == "nearby_meeting") JsonNull
                    else chatId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("proposalId", if (kind == "nearby_meeting") JsonNull
                    else proposalId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            })
            put("createdAt", createdAt.toString())
            put("readAt", readAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        }
    }
}
