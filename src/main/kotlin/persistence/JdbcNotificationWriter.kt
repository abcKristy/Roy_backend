package com.example.persistence

import kotlinx.serialization.json.JsonObject
import org.postgresql.util.PGobject
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

enum class NotificationEventType(val value: String) {
    REMINDER_1H("reminder_1h"),
    CHAT_MESSAGE("chat_message"),
    PROPOSAL_VOTE("proposal_vote"),
    PROPOSAL_READY("proposal_ready"),
    BOOKING_CREATED("booking_created"),
    BOOKING_CANCELLED("booking_cancelled"),
    NEARBY_MEETING("nearby_meeting"),
}

data class NotificationEventDraft(
    val recipientUserId: UUID,
    val eventType: NotificationEventType,
    val dedupeKey: String,
    val displaySnapshot: JsonObject,
    val pointId: UUID? = null,
    val chatId: UUID? = null,
    val proposalId: UUID? = null,
    /** Private weak reference; never include it in the public notification target. */
    val bookingId: UUID? = null,
)

sealed interface NotificationWriteResult {
    data class Created(val eventId: UUID, val pushJobsCreated: Int, val visibleInApp: Boolean) : NotificationWriteResult
    data class Duplicate(val eventId: UUID) : NotificationWriteResult
    data object RecipientUnavailable : NotificationWriteResult
    data object ExtraDisabled : NotificationWriteResult
}

/** Writes the event and all currently eligible device jobs in the caller's business transaction. */
class JdbcNotificationWriter {
    fun write(
        connection: Connection,
        barrier: RecipientBarrier,
        draft: NotificationEventDraft,
    ): NotificationWriteResult {
        require(!connection.autoCommit) { "Notifications require a business transaction" }
        require(draft.dedupeKey.isNotBlank())
        val snapshot = draft.displaySnapshot.toString()
        require(snapshot.toByteArray(StandardCharsets.UTF_8).size <= 16_384)
        val bookingEvent = draft.eventType in setOf(
            NotificationEventType.REMINDER_1H,
            NotificationEventType.BOOKING_CREATED,
            NotificationEventType.BOOKING_CANCELLED,
        )
        require(bookingEvent == (draft.bookingId != null)) { "Booking events require a private booking ID" }
        when (draft.eventType) {
            NotificationEventType.CHAT_MESSAGE ->
                require(draft.chatId != null) { "Chat notifications require a chat ID" }
            NotificationEventType.PROPOSAL_VOTE, NotificationEventType.PROPOSAL_READY,
            NotificationEventType.REMINDER_1H, NotificationEventType.BOOKING_CREATED,
            NotificationEventType.BOOKING_CANCELLED ->
                require(draft.chatId != null && draft.proposalId != null) {
                    "Proposal notifications require chat and proposal IDs"
                }
            NotificationEventType.NEARBY_MEETING ->
                require(draft.pointId != null && draft.proposalId != null) {
                    "Nearby notifications require point and proposal IDs"
                }
        }
        if (!barrier.isActiveSharedRecipient(connection, draft.recipientUserId)) {
            return NotificationWriteResult.RecipientUnavailable
        }
        findExistingIdOrNull(connection, draft)?.let { return NotificationWriteResult.Duplicate(it) }

        val settings = readSettings(connection, draft.recipientUserId)
        val channel = when (draft.eventType) {
            NotificationEventType.REMINDER_1H -> "REMINDERS"
            NotificationEventType.NEARBY_MEETING -> "EXTRA"
            else -> "PERSONAL"
        }
        val visible = if (channel == "EXTRA") settings.extraInApp else true
        val push = when (channel) {
            "REMINDERS" -> settings.remindersPush
            "EXTRA" -> settings.extraPush
            else -> settings.personalPush
        }
        if (channel == "EXTRA" && !visible && !push) return NotificationWriteResult.ExtraDisabled

        val eventId = UUID.randomUUID()
        val inserted = connection.prepareStatement(
            """INSERT INTO notification_events
                (id,recipient_user_id,channel,event_type,dedupe_key,point_id,chat_id,proposal_id,booking_id,
                 display_snapshot,visible_in_app,push_requested,created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,clock_timestamp())
               ON CONFLICT (recipient_user_id,dedupe_key) DO NOTHING
               RETURNING created_at"""
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, draft.recipientUserId)
            statement.setString(3, channel)
            statement.setString(4, draft.eventType.value)
            statement.setString(5, draft.dedupeKey)
            statement.setObject(6, draft.pointId)
            statement.setObject(7, draft.chatId)
            statement.setObject(8, draft.proposalId)
            statement.setObject(9, draft.bookingId)
            statement.setObject(10, PGobject().apply { type = "jsonb"; value = snapshot })
            statement.setBoolean(11, visible)
            statement.setBoolean(12, push)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getObject(1, OffsetDateTime::class.java) else null
            }
        }
        if (inserted == null) return NotificationWriteResult.Duplicate(
            checkNotNull(findExistingIdOrNull(connection, draft)) { "Notification dedupe conflict without an existing event" }
        )
        val jobs = if (push) insertPushJobs(connection, eventId, draft.recipientUserId, inserted) else 0
        return NotificationWriteResult.Created(eventId, jobs, visible)
    }

    private data class Settings(
        val remindersPush: Boolean,
        val personalPush: Boolean,
        val extraPush: Boolean,
        val extraInApp: Boolean,
    )

    private fun readSettings(connection: Connection, recipient: UUID): Settings = connection.prepareStatement(
        """SELECT reminders_push_enabled,personal_push_enabled,extra_push_enabled,extra_in_app_enabled
           FROM notification_settings WHERE user_id = ?"""
    ).use { statement ->
        statement.setObject(1, recipient)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Active recipient has no notification settings" }
            Settings(rows.getBoolean(1), rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4))
        }
    }

    private fun findExistingIdOrNull(connection: Connection, draft: NotificationEventDraft): UUID? = connection.prepareStatement(
        "SELECT id FROM notification_events WHERE recipient_user_id = ? AND dedupe_key = ?"
    ).use { statement ->
        statement.setObject(1, draft.recipientUserId)
        statement.setString(2, draft.dedupeKey)
        statement.executeQuery().use { rows ->
            if (rows.next()) rows.getObject(1, UUID::class.java) else null
        }
    }

    private fun insertPushJobs(connection: Connection, eventId: UUID, recipient: UUID, dueAt: OffsetDateTime): Int =
        connection.prepareStatement(
            """INSERT INTO push_delivery_jobs
                (id,notification_event_id,installation_id,status,attempts,next_attempt_at)
               SELECT gen_random_uuid(), ?,installation_id,'PENDING',0,?
               FROM device_registrations
               WHERE user_id = ? AND revoked_at IS NULL
               ON CONFLICT (notification_event_id,installation_id) DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, dueAt)
            statement.setObject(3, recipient)
            statement.executeUpdate()
        }
}
