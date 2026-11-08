package com.example.jobs

import com.example.notifications.NotificationView
import com.example.notifications.notificationJson
import com.example.notifications.readNotification
import com.example.persistence.DatabaseRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.time.Instant
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
                        rows.getObject("recipient_user_id", UUID::class.java),
                        readNotification(rows),
                    ))
                } }
            }
        }
        var count = 0
        for (event in events) {
            val notification = event.notification
            if (notification.id in seen) continue
            try {
                publish(event.recipientId, notification.id, notificationJson(notification), notification.createdAt)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                continue // Retry when the rolling window is scanned again.
            }
            seen[notification.id] = Unit
            count++
            while (seen.size > rememberedIds) seen.remove(seen.keys.first())
        }
        // Restart at the end to catch rows committed after we passed their insertion timestamp.
        after = if (events.size == maxEvents) events.last().notification.let { Position(it.createdAt, it.id) } else null
        return count
    }

    private data class Position(val createdAt: Instant, val id: UUID)

    private data class VisibleEvent(val recipientId: UUID, val notification: NotificationView)
}
