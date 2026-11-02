package com.example.jobs

import com.example.persistence.DatabaseRuntime
import com.example.persistence.JdbcNotificationRecipients
import com.example.persistence.JdbcNotificationWriter
import com.example.persistence.NotificationEventDraft
import com.example.persistence.NotificationEventType
import com.example.persistence.NotificationWriteResult
import com.example.persistence.RecipientBarrier
import com.example.persistence.RecipientLockClaim
import com.example.persistence.RecipientLockMode
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ReminderTick(val bookingsVisited: Int, val eventsCreated: Int, val missedWindowGauge: Long)

/** Poll at most once a minute. DB time and a two-minute due window decide eligibility. */
class ReminderScheduler(
    private val database: DatabaseRuntime,
    private val recipients: JdbcNotificationRecipients = JdbcNotificationRecipients(),
    private val notifications: JdbcNotificationWriter = JdbcNotificationWriter(),
) {
    private val created = AtomicLong()
    private val missedGauge = AtomicLong()

    fun createdCount(): Long = created.get()
    fun missedWindowGauge(): Long = missedGauge.get()

    suspend fun tick(limit: Int = 100): ReminderTick {
        require(limit in 1..500)
        val ids = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                """SELECT b.id FROM bookings AS b JOIN meeting_proposals AS p ON p.id=b.proposal_id
                   WHERE b.status='ACTIVE' AND p.starts_at>clock_timestamp()+interval '58 minutes'
                     AND p.starts_at<=clock_timestamp()+interval '1 hour'
                     AND b.created_at<=p.starts_at-interval '1 hour'
                   ORDER BY p.starts_at,b.id LIMIT ?"""
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java))
                } }
            }
        }
        var produced = 0
        for (id in ids) produced += schedule(id)
        created.addAndGet(produced.toLong())
        val missed = database.withReadOnlyJdbcTransaction { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """SELECT count(*) FROM bookings AS b JOIN meeting_proposals AS p ON p.id=b.proposal_id
                       WHERE b.status='ACTIVE' AND p.starts_at>clock_timestamp()
                         AND p.starts_at<clock_timestamp()+interval '58 minutes'
                         AND b.created_at<=p.starts_at-interval '1 hour'
                         AND NOT EXISTS (SELECT 1 FROM notification_events AS e
                           WHERE e.booking_id=b.id AND e.event_type='reminder_1h')"""
                ).use { rows -> rows.next(); rows.getLong(1) }
            }
        }
        missedGauge.set(missed)
        return ReminderTick(ids.size, produced, missed)
    }

    private suspend fun schedule(bookingId: UUID): Int = database.withRetryingJdbcTransaction { connection ->
        val pointId = connection.prepareStatement(
            """SELECT pt.point_id FROM bookings AS b JOIN meeting_proposals AS p ON p.id=b.proposal_id
               JOIN chats AS c ON c.id=p.chat_id JOIN point_topics AS pt ON pt.id=c.point_topic_id
               WHERE b.id=?"""
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: return@withRetryingJdbcTransaction 0
        if (!lockPoint(connection, pointId)) return@withRetryingJdbcTransaction 0
        val proposal = connection.prepareStatement(
            """SELECT p.id,p.chat_id,p.starts_at,b.created_at,b.status
               FROM bookings AS b JOIN meeting_proposals AS p ON p.id=b.proposal_id
               WHERE b.id=? FOR UPDATE OF p,b"""
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.executeQuery().use { rows -> if (rows.next()) LockedBooking(
                rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java),
                rows.getObject(3, OffsetDateTime::class.java), rows.getObject(4, OffsetDateTime::class.java),
                rows.getString(5)) else null }
        } ?: return@withRetryingJdbcTransaction 0
        val eligible = connection.prepareStatement(
            """SELECT ?='ACTIVE' AND ?<=?-interval '1 hour'
                    AND clock_timestamp()>=?-interval '1 hour'
                    AND clock_timestamp()<?-interval '58 minutes'
                    AND clock_timestamp()<?"""
        ).use { statement ->
            statement.setString(1, proposal.status)
            statement.setObject(2, proposal.bookedAt)
            statement.setObject(3, proposal.startsAt)
            statement.setObject(4, proposal.startsAt)
            statement.setObject(5, proposal.startsAt)
            statement.setObject(6, proposal.startsAt)
            statement.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
        }
        if (!eligible) return@withRetryingJdbcTransaction 0
        val recipientIds = recipients.activeChatMembers(connection, proposal.chatId)
        val barrier = RecipientBarrier.acquire(connection,
            recipientIds.map { RecipientLockClaim(it, RecipientLockMode.SHARED) })
        recipientIds.count { recipientId ->
            notifications.write(connection, barrier, NotificationEventDraft(
                recipientId, NotificationEventType.REMINDER_1H, "reminder_1h:$bookingId",
                buildJsonObject { put("title", "Встреча через час"); put("body", "Напоминание о встрече") },
                pointId = pointId, chatId = proposal.chatId, proposalId = proposal.proposalId,
                bookingId = bookingId,
            )) is NotificationWriteResult.Created
        }
    }

    private data class LockedBooking(val proposalId: UUID, val chatId: UUID, val startsAt: OffsetDateTime,
                                     val bookedAt: OffsetDateTime, val status: String)

    private fun lockPoint(connection: Connection, id: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM points WHERE id=? FOR UPDATE"
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { it.next() }
    }
}
