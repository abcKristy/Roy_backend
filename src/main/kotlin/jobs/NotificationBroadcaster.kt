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

    private val seen = LinkedHashMap<UUID, Position>()
    private var forwardAfter: Position? = null
    private var recheckAfter: Position? = null
    private var evictedThrough: Position? = null

    /** Call from one runtime loop; the callback runs outside the read transaction. */
    suspend fun tick(maxEvents: Int = 5_000): Int {
        require(maxEvents in 1..20_000)
        val previousWatermark = forwardAfter
        val forward = load(previousWatermark, through = null, maxEvents)
        var published = publishNew(forward)
        forward.lastOrNull()?.let { forwardAfter = it.position }

        val spare = maxEvents - forward.size
        if (previousWatermark != null && spare > 0) {
            // Only recheck positions whose delivery can still be deduplicated.
            val lowerBound = listOfNotNull(recheckAfter, evictedThrough).maxOrNull()
            val recent = load(lowerBound, through = previousWatermark, spare)
            published += publishNew(recent)
            recheckAfter = if (recent.size == spare) recent.last().position else null
        }
        return published
    }

    private suspend fun load(after: Position?, through: Position?, limit: Int): List<VisibleEvent> =
        database.withReadOnlyJdbcTransaction { connection ->
            val afterFilter = if (after == null) "" else "AND (created_at,id)>(?,?)"
            val throughFilter = if (through == null) "" else "AND (created_at,id)<=(?,?)"
            connection.prepareStatement(
                """SELECT id,recipient_user_id,event_type,point_id,chat_id,proposal_id,
                          display_snapshot,created_at,read_at
                   FROM notification_events
                   WHERE visible_in_app=true
                     AND created_at>=statement_timestamp()-(? * interval '1 minute')
                     $afterFilter
                     $throughFilter
                   ORDER BY created_at,id LIMIT ?"""
            ).use { statement ->
                statement.setInt(1, lookbackMinutes)
                var index = 2
                for (position in listOfNotNull(after, through)) {
                    statement.setObject(index++, position.createdAt.atOffset(ZoneOffset.UTC))
                    statement.setObject(index++, position.id)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(VisibleEvent(
                        rows.getObject("recipient_user_id", UUID::class.java),
                        readNotification(rows),
                    ))
                } }
            }
        }

    private suspend fun publishNew(events: List<VisibleEvent>): Int {
        var count = 0
        for (event in events) {
            val notification = event.notification
            val position = event.position
            val floor = evictedThrough
            if (notification.id in seen || (floor != null && position <= floor)) continue
            try {
                publish(event.recipientId, notification.id, notificationJson(notification), notification.createdAt)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                continue // Retry when the rolling window is scanned again.
            }
            seen[notification.id] = position
            count++
            if (seen.size > rememberedIds) {
                val oldest = seen.entries.first()
                if (floor == null || oldest.value > floor) evictedThrough = oldest.value
                seen.remove(oldest.key)
            }
        }
        return count
    }

    private data class Position(val createdAt: Instant, val id: UUID) : Comparable<Position> {
        // PostgreSQL compares UUID bytes unsigned, unlike java.util.UUID.compareTo.
        override fun compareTo(other: Position): Int = compareValuesBy(this, other, Position::createdAt, { it.id.toString() })
    }

    private data class VisibleEvent(val recipientId: UUID, val notification: NotificationView) {
        val position = Position(notification.createdAt, notification.id)
    }
}
