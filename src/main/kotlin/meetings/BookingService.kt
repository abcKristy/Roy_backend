package com.example.meetings

import com.example.chats.MessageView
import com.example.persistence.DatabaseRuntime
import com.example.persistence.JdbcIdempotencyOperations
import com.example.persistence.JdbcNotificationRecipients
import com.example.persistence.JdbcNotificationWriter
import com.example.persistence.JdbcSystemMessageWriter
import com.example.persistence.NotificationEventDraft
import com.example.persistence.NotificationEventType
import com.example.persistence.RecipientBarrier
import com.example.persistence.RecipientLockClaim
import com.example.persistence.RecipientLockMode
import com.example.persistence.StoredSystemMessage
import com.example.persistence.SystemMessageDraft
import com.example.persistence.SystemMessageKind
import com.example.persistence.SystemMessageReason
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class BookMeetingCommand(
    val actorId: UUID, val proposalId: UUID, val idempotencyKey: UUID,
    val confirmOverlap: Boolean, val expectedRevision: Long,
)

data class CloseMeetingCommand(
    val actorId: UUID, val proposalId: UUID, val idempotencyKey: UUID, val expectedRevision: Long,
)

sealed interface BookMeetingResult {
    data class Created(
        val proposal: ProposalView, val responseSnapshot: JsonObject,
        val systemMessages: List<MessageView>, val bookingChange: BookingChange,
    ) : BookMeetingResult
    data class Replay(val responseSnapshot: JsonObject) : BookMeetingResult
    data class OverlapConfirmRequired(val overlaps: List<BookingConflictView>) : BookMeetingResult
    data object KeyReused : BookMeetingResult
    data object Gone : BookMeetingResult
    data object NotMember : BookMeetingResult
    data object Stale : BookMeetingResult
    data object AlreadyStarted : BookMeetingResult
    data object VoteRequired : BookMeetingResult
    data object InsufficientVotes : BookMeetingResult
    data object ProposalClosed : BookMeetingResult
    data object AlreadyBooked : BookMeetingResult
}

sealed interface CloseMeetingResult {
    data class Created(
        val proposal: ProposalView, val responseSnapshot: JsonObject,
        val systemMessages: List<MessageView>, val bookingChange: BookingChange?,
    ) : CloseMeetingResult
    data class Replay(val responseSnapshot: JsonObject) : CloseMeetingResult
    data object KeyReused : CloseMeetingResult
    data object Gone : CloseMeetingResult
    data object NotMember : CloseMeetingResult
    data object Stale : CloseMeetingResult
    data object AlreadyStarted : CloseMeetingResult
    data object NotAuthor : CloseMeetingResult
    data object AlreadyClosed : CloseMeetingResult
}

/** Booking and close are serialized by their point row, shared by all chats of that point. */
class BookingService(
    private val database: DatabaseRuntime,
    private val idempotency: JdbcIdempotencyOperations = JdbcIdempotencyOperations(),
    private val systemMessages: JdbcSystemMessageWriter = JdbcSystemMessageWriter(),
    private val recipients: JdbcNotificationRecipients = JdbcNotificationRecipients(),
    private val notifications: JdbcNotificationWriter = JdbcNotificationWriter(),
) {
    private val projection = MeetingProjection()
    suspend fun book(
        command: BookMeetingCommand,
        successBody: (Connection, ProposalView) -> JsonObject,
    ): BookMeetingResult {
        val hash = JdbcIdempotencyOperations.requestHash("book_meeting", listOf(command.proposalId), buildJsonObject {})
        return database.withRetryingJdbcTransaction { connection ->
            when (val decision = idempotency.lockActorAndCheck(
                connection, command.actorId, "book_meeting", command.idempotencyKey, hash,
            )) {
                is JdbcIdempotencyOperations.Decision.Replay ->
                    return@withRetryingJdbcTransaction BookMeetingResult.Replay(checkNotNull(decision.record.responseSnapshot))
                JdbcIdempotencyOperations.Decision.KeyReused ->
                    return@withRetryingJdbcTransaction BookMeetingResult.KeyReused
                JdbcIdempotencyOperations.Decision.New -> Unit
            }
            val pointId = findPointId(connection, command.proposalId)
                ?: return@withRetryingJdbcTransaction BookMeetingResult.Gone
            if (!lockPoint(connection, pointId)) return@withRetryingJdbcTransaction BookMeetingResult.Gone
            val proposal = lockProposal(connection, command.proposalId, pointId)
                ?: return@withRetryingJdbcTransaction BookMeetingResult.Gone
            if (!activeMember(connection, proposal.chatId, command.actorId)) {
                return@withRetryingJdbcTransaction BookMeetingResult.NotMember
            }
            if (command.expectedRevision != proposal.revision) return@withRetryingJdbcTransaction BookMeetingResult.Stale
            if (proposal.lifecycle != "OPEN") return@withRetryingJdbcTransaction BookMeetingResult.ProposalClosed
            val now = databaseNow(connection)
            if (!now.isBefore(proposal.startsAt)) return@withRetryingJdbcTransaction BookMeetingResult.AlreadyStarted
            if (activeBooking(connection, command.proposalId) != null) {
                return@withRetryingJdbcTransaction BookMeetingResult.AlreadyBooked
            }
            if (!hasVote(connection, command.proposalId, command.actorId)) {
                return@withRetryingJdbcTransaction BookMeetingResult.VoteRequired
            }
            if (voteCount(connection, command.proposalId) <= 1) {
                return@withRetryingJdbcTransaction BookMeetingResult.InsufficientVotes
            }
            val overlaps = overlaps(connection, pointId, proposal.startsAt, proposal.endsAt)
            if (overlaps.isNotEmpty() && !command.confirmOverlap) {
                return@withRetryingJdbcTransaction BookMeetingResult.OverlapConfirmRequired(overlaps)
            }
            val bookingId = UUID.randomUUID()
            connection.prepareStatement(
                """INSERT INTO bookings (id,proposal_id,booked_by_user_id,status,created_at)
                   VALUES (?,?,?,'ACTIVE',?)"""
            ).use { statement ->
                statement.setObject(1, bookingId)
                statement.setObject(2, command.proposalId)
                statement.setObject(3, command.actorId)
                statement.setObject(4, now.atOffset(ZoneOffset.UTC))
                check(statement.executeUpdate() == 1)
            }
            bumpRevision(connection, command.proposalId, now)
            touchPoint(connection, pointId, now)
            val draft = SystemMessageDraft(
                SystemMessageKind.MEETING_BOOKED, command.actorId, pointId, proposal.chatId,
                proposalId = command.proposalId, bookingId = bookingId,
                startsAt = proposal.startsAt, endsAt = proposal.endsAt,
            )
            val message = appendMessage(connection, draft)
            notifyChat(connection, proposal.chatId, command.actorId, pointId, command.proposalId,
                bookingId, NotificationEventType.BOOKING_CREATED, "booking_created:$bookingId",
                "Встреча забронирована", "Участники подтвердили встречу")
            val view = checkNotNull(projection.read(connection, command.proposalId, command.actorId))
            val snapshot = responseSnapshot(201, successBody(connection, view))
            idempotency.recordSuccess(connection, command.actorId, "book_meeting", command.idempotencyKey,
                hash, bookingId, snapshot)
            BookMeetingResult.Created(view, snapshot, listOf(message), BookingChange(bookingId, "active", null))
        }
    }

    suspend fun close(
        command: CloseMeetingCommand,
        successBody: (Connection, ProposalView) -> JsonObject,
    ): CloseMeetingResult {
        val hash = JdbcIdempotencyOperations.requestHash("close_meeting", listOf(command.proposalId), buildJsonObject {})
        return database.withRetryingJdbcTransaction { connection ->
            when (val decision = idempotency.lockActorAndCheck(
                connection, command.actorId, "close_meeting", command.idempotencyKey, hash,
            )) {
                is JdbcIdempotencyOperations.Decision.Replay ->
                    return@withRetryingJdbcTransaction CloseMeetingResult.Replay(checkNotNull(decision.record.responseSnapshot))
                JdbcIdempotencyOperations.Decision.KeyReused ->
                    return@withRetryingJdbcTransaction CloseMeetingResult.KeyReused
                JdbcIdempotencyOperations.Decision.New -> Unit
            }
            val pointId = findPointId(connection, command.proposalId)
                ?: return@withRetryingJdbcTransaction CloseMeetingResult.Gone
            if (!lockPoint(connection, pointId)) return@withRetryingJdbcTransaction CloseMeetingResult.Gone
            val proposal = lockProposal(connection, command.proposalId, pointId)
                ?: return@withRetryingJdbcTransaction CloseMeetingResult.Gone
            if (!activeMember(connection, proposal.chatId, command.actorId)) {
                return@withRetryingJdbcTransaction CloseMeetingResult.NotMember
            }
            if (proposal.proposerUserId != command.actorId) return@withRetryingJdbcTransaction CloseMeetingResult.NotAuthor
            if (command.expectedRevision != proposal.revision) return@withRetryingJdbcTransaction CloseMeetingResult.Stale
            if (proposal.lifecycle != "OPEN") return@withRetryingJdbcTransaction CloseMeetingResult.AlreadyClosed
            val now = databaseNow(connection)
            if (!now.isBefore(proposal.startsAt)) return@withRetryingJdbcTransaction CloseMeetingResult.AlreadyStarted

            val cancelledBooking = connection.prepareStatement(
                """UPDATE bookings SET status='CANCELLED',cancelled_at=?,cancel_reason='CLOSED_BY_AUTHOR'
                   WHERE proposal_id=? AND status='ACTIVE' RETURNING id"""
            ).use { statement ->
                statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                statement.setObject(2, command.proposalId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
            }
            connection.prepareStatement(
                """UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR',
                   revision=revision+1,updated_at=? WHERE id=?"""
            ).use { statement ->
                statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                statement.setObject(2, command.proposalId)
                check(statement.executeUpdate() == 1)
            }
            touchPoint(connection, pointId, now)
            val events = mutableListOf<MessageView>()
            if (cancelledBooking != null) {
                cancelReminderJobs(connection, cancelledBooking)
                events += appendMessage(connection, SystemMessageDraft(
                    SystemMessageKind.MEETING_BOOKING_CANCELLED, command.actorId, pointId, proposal.chatId,
                    proposalId = command.proposalId, bookingId = cancelledBooking,
                    startsAt = proposal.startsAt, endsAt = proposal.endsAt,
                    reason = SystemMessageReason.CLOSED_BY_AUTHOR,
                ))
            }
            events += appendMessage(connection, SystemMessageDraft(
                SystemMessageKind.MEETING_CLOSED, command.actorId, pointId, proposal.chatId,
                proposalId = command.proposalId, startsAt = proposal.startsAt, endsAt = proposal.endsAt,
            ))
            if (cancelledBooking != null) notifyChat(connection, proposal.chatId, command.actorId,
                pointId, command.proposalId, cancelledBooking, NotificationEventType.BOOKING_CANCELLED,
                "booking_cancelled:$cancelledBooking", "Бронь встречи отменена", "Автор закрыл предложение")
            val view = checkNotNull(projection.read(connection, command.proposalId, command.actorId))
            val snapshot = responseSnapshot(200, successBody(connection, view))
            idempotency.recordSuccess(connection, command.actorId, "close_meeting", command.idempotencyKey,
                hash, command.proposalId, snapshot)
            CloseMeetingResult.Created(view, snapshot, events,
                cancelledBooking?.let { BookingChange(it, "cancelled", "closed_by_author") })
        }
    }

    private data class LockedProposal(
        val chatId: UUID, val proposerUserId: UUID?, val startsAt: Instant, val endsAt: Instant,
        val lifecycle: String, val revision: Long,
    )

    private fun findPointId(connection: Connection, proposalId: UUID): UUID? = connection.prepareStatement(
        """SELECT topic.point_id FROM meeting_proposals AS proposal
           JOIN chats AS chat ON chat.id=proposal.chat_id
           JOIN point_topics AS topic ON topic.id=chat.point_topic_id
           WHERE proposal.id=?"""
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    private fun lockPoint(connection: Connection, pointId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM points WHERE id=? FOR UPDATE"
    ).use { statement ->
        statement.setObject(1, pointId)
        statement.executeQuery().use { it.next() }
    }

    private fun lockProposal(connection: Connection, proposalId: UUID, pointId: UUID): LockedProposal? = connection.prepareStatement(
        """SELECT proposal.chat_id,proposal.proposer_user_id,proposal.starts_at,proposal.ends_at,
                  proposal.lifecycle,proposal.revision
           FROM meeting_proposals AS proposal
           JOIN chats AS chat ON chat.id=proposal.chat_id
           JOIN point_topics AS topic ON topic.id=chat.point_topic_id
           WHERE proposal.id=? AND topic.point_id=? FOR UPDATE OF proposal"""
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.setObject(2, pointId)
        statement.executeQuery().use { rows -> if (!rows.next()) null else LockedProposal(
            rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java),
            rows.getObject(3, OffsetDateTime::class.java).toInstant(),
            rows.getObject(4, OffsetDateTime::class.java).toInstant(), rows.getString(5), rows.getLong(6),
        ) }
    }

    private fun activeMember(connection: Connection, chatId: UUID, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.setObject(2, userId)
        statement.executeQuery().use { it.next() }
    }

    private fun activeBooking(connection: Connection, proposalId: UUID): UUID? = connection.prepareStatement(
        "SELECT id FROM bookings WHERE proposal_id=? AND status='ACTIVE'"
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    private fun hasVote(connection: Connection, proposalId: UUID, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM votes WHERE proposal_id=? AND user_id=?"
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.setObject(2, userId)
        statement.executeQuery().use { it.next() }
    }

    private fun voteCount(connection: Connection, proposalId: UUID): Int = connection.prepareStatement(
        "SELECT count(*) FROM votes WHERE proposal_id=?"
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
    }

    private fun overlaps(connection: Connection, pointId: UUID, start: Instant, end: Instant): List<BookingConflictView> =
        connection.prepareStatement(
            """SELECT booking.id,proposal.id,proposal.starts_at,proposal.ends_at,booking.created_at,booking.booking_order
               FROM bookings AS booking
               JOIN meeting_proposals AS proposal ON proposal.id=booking.proposal_id
               JOIN chats AS chat ON chat.id=proposal.chat_id
               JOIN point_topics AS topic ON topic.id=chat.point_topic_id
               WHERE topic.point_id=? AND booking.status='ACTIVE'
                 AND proposal.starts_at<? AND proposal.ends_at>?
               ORDER BY booking.booking_order"""
        ).use { statement ->
            statement.setObject(1, pointId)
            statement.setObject(2, end.atOffset(ZoneOffset.UTC))
            statement.setObject(3, start.atOffset(ZoneOffset.UTC))
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(BookingConflictView(
                    rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java),
                    rows.getObject(3, OffsetDateTime::class.java).toInstant(),
                    rows.getObject(4, OffsetDateTime::class.java).toInstant(),
                    rows.getObject(5, OffsetDateTime::class.java).toInstant(), rows.getLong(6),
                ))
            } }
        }

    private fun bumpRevision(connection: Connection, proposalId: UUID, now: Instant) {
        connection.prepareStatement(
            "UPDATE meeting_proposals SET revision=revision+1,updated_at=? WHERE id=?"
        ).use { statement ->
            statement.setObject(1, now.atOffset(ZoneOffset.UTC))
            statement.setObject(2, proposalId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun touchPoint(connection: Connection, pointId: UUID, now: Instant) {
        connection.prepareStatement("UPDATE points SET updated_at=? WHERE id=?").use { statement ->
            statement.setObject(1, now.atOffset(ZoneOffset.UTC))
            statement.setObject(2, pointId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun appendMessage(connection: Connection, draft: SystemMessageDraft): MessageView {
        val stored: StoredSystemMessage = systemMessages.append(connection, UUID.randomUUID(), draft)
        val payload = connection.prepareStatement("SELECT system_payload FROM messages WHERE id=?").use { statement ->
            statement.setObject(1, stored.id)
            statement.executeQuery().use { rows ->
                check(rows.next())
                Json.parseToJsonElement(rows.getString(1)) as JsonObject
            }
        }
        return MessageView(stored.id, stored.chatId, stored.messageSeq, "SYSTEM", null, null, null,
            null, draft.kind.databaseValue, payload, stored.createdAt)
    }

    private fun notifyChat(
        connection: Connection, chatId: UUID, actorId: UUID, pointId: UUID, proposalId: UUID,
        bookingId: UUID, type: NotificationEventType, dedupe: String, title: String, body: String,
    ) {
        val recipientIds = recipients.activeChatMembers(connection, chatId, actorId)
        val barrier = RecipientBarrier.acquire(connection,
            recipientIds.map { RecipientLockClaim(it, RecipientLockMode.SHARED) })
        for (recipientId in recipientIds) notifications.write(connection, barrier, NotificationEventDraft(
            recipientId, type, dedupe, buildJsonObject { put("title", title); put("body", body) },
            pointId = pointId, chatId = chatId, proposalId = proposalId, bookingId = bookingId,
        ))
    }

    private fun cancelReminderJobs(connection: Connection, bookingId: UUID) {
        connection.prepareStatement(
            """UPDATE push_delivery_jobs AS job SET status='CANCELLED',claim_token=NULL,lease_until=NULL,
               sent_at=NULL WHERE job.status IN ('PENDING','PROCESSING')
               AND EXISTS (SELECT 1 FROM notification_events AS event
                           WHERE event.id=job.notification_event_id AND event.booking_id=?
                             AND event.event_type='reminder_1h')"""
        ).use { statement -> statement.setObject(1, bookingId); statement.executeUpdate() }
    }

    private fun responseSnapshot(status: Int, body: JsonObject): JsonObject = buildJsonObject {
        put("status", status)
        put("body", body)
    }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }
}
