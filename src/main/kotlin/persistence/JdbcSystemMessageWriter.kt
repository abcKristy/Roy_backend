package com.example.persistence

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.util.UUID

enum class SystemMessageKind(val databaseValue: String) {
    MEETING_PROPOSED("meeting_proposed"),
    MEETING_READY("meeting_ready"),
    MEETING_BOOKED("meeting_booked"),
    MEETING_BOOKING_CANCELLED("meeting_booking_cancelled"),
    MEETING_CLOSED("meeting_closed"),
    MEMBER_JOINED("member_joined"),
    MEMBER_LEFT("member_left"),
    POINT_DELETED("point_deleted"),
}

enum class SystemMessageReason(val databaseValue: String) {
    INSUFFICIENT_VOTES("insufficient_votes"),
    POINT_DELETED("point_deleted"),
    CLOSED_BY_AUTHOR("closed_by_author"),
}

data class SystemMessageDraft(
    val kind: SystemMessageKind,
    val actorUserId: UUID?,
    val pointId: UUID,
    val chatId: UUID,
    val proposalId: UUID? = null,
    val bookingId: UUID? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val reason: SystemMessageReason? = null,
)

data class StoredSystemMessage(val id: UUID, val chatId: UUID, val messageSeq: Long, val createdAt: Instant)

/** Call after acquiring the owning point lock; this locks the chat and allocates one sequence. */
class JdbcSystemMessageWriter {
    fun append(
        connection: Connection,
        id: UUID,
        draft: SystemMessageDraft,
    ): StoredSystemMessage {
        requireTransaction(connection)
        val seq = connection.prepareStatement(
            """UPDATE chats AS c SET last_message_seq = c.last_message_seq + 1
               FROM point_topics AS pt
               WHERE c.id = ? AND c.point_topic_id = pt.id AND pt.point_id = ?
               RETURNING c.last_message_seq"""
        ).use { statement ->
            statement.setObject(1, draft.chatId)
            statement.setObject(2, draft.pointId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Chat does not belong to the specified point" }
                rows.getLong(1)
            }
        }

        val createdAt = connection.prepareStatement(
            """INSERT INTO messages
               (id, chat_id, message_seq, type, system_type, system_payload, created_at)
               VALUES (?, ?, ?, 'SYSTEM', ?, ?, clock_timestamp())
               RETURNING created_at"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, draft.chatId)
            statement.setLong(3, seq)
            statement.setString(4, draft.kind.databaseValue)
            statement.setObject(5, jsonb(draft.payload()))
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant()
            }
        }
        return StoredSystemMessage(id, draft.chatId, seq, createdAt)
    }
}

private fun SystemMessageDraft.payload(): JsonObject = JsonObject(
    mapOf(
        "kind" to JsonPrimitive(kind.databaseValue),
        "actorUserId" to actorUserId?.let { JsonPrimitive(it.toString()) }.orNull(),
        "pointId" to JsonPrimitive(pointId.toString()),
        "chatId" to JsonPrimitive(chatId.toString()),
        "proposalId" to proposalId?.let { JsonPrimitive(it.toString()) }.orNull(),
        "bookingId" to bookingId?.let { JsonPrimitive(it.toString()) }.orNull(),
        "startsAt" to startsAt?.let { JsonPrimitive(it.toString()) }.orNull(),
        "endsAt" to endsAt?.let { JsonPrimitive(it.toString()) }.orNull(),
        "reason" to reason?.let { JsonPrimitive(it.databaseValue) }.orNull(),
    )
)

private fun JsonPrimitive?.orNull() = this ?: JsonNull
