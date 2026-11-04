package com.example.accounts

import com.example.auth.PasswordHashingWork
import com.example.auth.SessionIdentity
import com.example.persistence.DatabaseRuntime
import com.example.persistence.RecipientBarrier
import com.example.persistence.RecipientLockClaim
import com.example.persistence.RecipientLockMode
import com.example.persistence.JdbcSystemMessageWriter
import com.example.persistence.JdbcNotificationWriter
import com.example.persistence.JdbcNotificationRecipients
import com.example.persistence.SystemMessageDraft
import com.example.persistence.SystemMessageKind
import com.example.persistence.SystemMessageReason
import com.example.persistence.NotificationEventDraft
import com.example.persistence.NotificationEventType
import com.example.meetings.BookingCancellationPolicy
import com.example.meetings.MeetingEmission
import com.example.meetings.MeetingProjection
import com.example.meetings.ProposalView
import com.example.meetings.BookingChange
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

sealed interface AccountDeletionResult {
    data class Deleted(val removedPoints: List<RemovedPoint>, val revokedSessionIds: List<UUID>,
                       val meetingChanges: List<DeletionMeetingChange>,
                       val membershipMessages: List<MeetingEmission>) : AccountDeletionResult
    data object WrongPassword : AccountDeletionResult
    data object SessionExpired : AccountDeletionResult
}

data class RemovedPoint(val pointId: UUID, val formerMemberIds: List<UUID>)
data class DeletionMeetingChange(val proposal: ProposalView, val messages: List<MeetingEmission>,
                                 val bookingChange: BookingChange?)

/** Removes private state in one transaction while retaining the anonymized report tombstone. */
class AccountDeletionService(
    private val database: DatabaseRuntime,
    private val passwordHashing: PasswordHashingWork,
    private val systemMessages: JdbcSystemMessageWriter = JdbcSystemMessageWriter(),
    private val notificationRecipients: JdbcNotificationRecipients = JdbcNotificationRecipients(),
    private val notificationWriter: JdbcNotificationWriter = JdbcNotificationWriter(),
    private val projection: MeetingProjection = MeetingProjection(),
    private val cancellationPolicy: BookingCancellationPolicy = BookingCancellationPolicy(),
) {
    suspend fun delete(identity: SessionIdentity, currentPassword: String): AccountDeletionResult {
        val snapshot = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                "SELECT password_hash,auth_version FROM users WHERE id=? AND deleted_at IS NULL"
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) to rows.getInt(2) else null
                }
            }
        } ?: return AccountDeletionResult.SessionExpired
        if (snapshot.second != identity.authVersion) return AccountDeletionResult.SessionExpired
        if (!passwordHashing.verify(currentPassword, snapshot.first)) return AccountDeletionResult.WrongPassword

        return database.withRetryingJdbcTransaction { connection ->
            val locked = connection.prepareStatement(
                "SELECT password_hash,auth_version,avatar_object_key,deleted_at FROM users WHERE id=? FOR NO KEY UPDATE"
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) LockedUser(rows.getString(1), rows.getInt(2), rows.getString(3),
                        rows.getObject(4, OffsetDateTime::class.java) != null) else null
                }
            } ?: return@withRetryingJdbcTransaction AccountDeletionResult.SessionExpired
            if (locked.deleted || locked.hash != snapshot.first || locked.authVersion != snapshot.second ||
                !activeSession(connection, identity)) return@withRetryingJdbcTransaction AccountDeletionResult.SessionExpired

            // The actor lock stabilizes this set. Other writers serialize on point locks.
            val pointIds = ids(connection,
                """SELECT DISTINCT point_id FROM (
                     SELECT pt.point_id FROM chat_memberships m JOIN chats c ON c.id=m.chat_id
                       JOIN point_topics pt ON pt.id=c.point_topic_id WHERE m.user_id=?
                     UNION SELECT pt.point_id FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id
                       JOIN chats c ON c.id=p.chat_id JOIN point_topics pt ON pt.id=c.point_topic_id WHERE v.user_id=?
                     UNION SELECT pt.point_id FROM meeting_proposals p JOIN chats c ON c.id=p.chat_id
                       JOIN point_topics pt ON pt.id=c.point_topic_id WHERE p.proposer_user_id=?
                     UNION SELECT pt.point_id FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id
                       JOIN chats c ON c.id=p.chat_id JOIN point_topics pt ON pt.id=c.point_topic_id WHERE b.booked_by_user_id=?
                     UNION SELECT pt.point_id FROM messages msg JOIN chats c ON c.id=msg.chat_id
                       JOIN point_topics pt ON pt.id=c.point_topic_id WHERE msg.author_user_id=?
                     UNION SELECT id FROM points WHERE created_by_user_id=?
                   ) touched ORDER BY point_id""", identity.userId, repeatUserId = 6)
            for (pointId in pointIds) lock(connection, "points", pointId)
            val chatIds = ids(connection,
                """SELECT c.id FROM chats c JOIN point_topics pt ON pt.id=c.point_topic_id
                   WHERE pt.point_id=ANY(?) ORDER BY c.id""", pointIds)
            for (chatId in chatIds) lock(connection, "chats", chatId)
            val proposalIds = ids(connection,
                """SELECT DISTINCT proposal_id FROM (
                     SELECT proposal_id FROM votes WHERE user_id=?
                     UNION SELECT id FROM meeting_proposals WHERE proposer_user_id=?
                   ) touched ORDER BY proposal_id""", identity.userId, repeatUserId = 2)
            for (proposalId in proposalIds) lock(connection, "meeting_proposals", proposalId)
            val votedProposalIds = ids(connection,
                "SELECT proposal_id FROM votes WHERE user_id=? ORDER BY proposal_id", identity.userId)
            val activeChatIds = ids(connection,
                "SELECT chat_id FROM chat_memberships WHERE user_id=? AND left_at IS NULL ORDER BY chat_id", identity.userId)

            execute(connection, "DELETE FROM votes WHERE user_id=?", identity.userId)
            execute(connection,
                """UPDATE meeting_proposals AS p SET revision=revision+1,updated_at=clock_timestamp()
                   WHERE id=ANY(?)""", votedProposalIds)
            val cancelledBookings = cancellationPolicy.cancelInsufficientBookings(connection, votedProposalIds)
            execute(connection,
                """UPDATE push_delivery_jobs AS j SET status='CANCELLED',claim_token=NULL,lease_until=NULL,
                   completed_at=clock_timestamp() FROM notification_events AS e
                   WHERE j.notification_event_id=e.id AND e.booking_id IN
                     (SELECT b.id FROM bookings b WHERE b.proposal_id=ANY(?) AND b.status='CANCELLED')
                   AND e.event_type='reminder_1h' AND j.status IN ('PENDING','PROCESSING')""", votedProposalIds)
            // The author no longer controls an unbooked proposal after the votes and bookings settle.
            val closedProposalIds = updateReturningIds(connection,
                """UPDATE meeting_proposals AS p SET lifecycle='CLOSED',closed_reason='AUTHOR_DELETED',
                   revision=revision+1,updated_at=clock_timestamp()
                   WHERE proposer_user_id=? AND lifecycle='OPEN' AND NOT EXISTS
                   (SELECT 1 FROM bookings b WHERE b.proposal_id=p.id AND b.status='ACTIVE')
                   RETURNING p.id""", identity.userId)
            execute(connection, "DELETE FROM chat_memberships WHERE user_id=?", identity.userId)

            val removedPoints = pointIds.mapNotNull { pointId ->
                val active = connection.prepareStatement(
                    """SELECT 1 FROM chat_memberships m JOIN chats c ON c.id=m.chat_id
                       JOIN point_topics pt ON pt.id=c.point_topic_id
                       WHERE pt.point_id=? AND m.left_at IS NULL LIMIT 1"""
                ).use { statement ->
                    statement.setObject(1, pointId)
                    statement.executeQuery().use { it.next() }
                }
                if (active) null else {
                    val formerMemberIds = connection.prepareStatement(
                        """SELECT DISTINCT m.user_id FROM chat_memberships m JOIN chats c ON c.id=m.chat_id
                           JOIN point_topics pt ON pt.id=c.point_topic_id WHERE pt.point_id=?"""
                    ).use { statement ->
                        statement.setObject(1, pointId)
                        statement.executeQuery().use { rows -> buildList {
                            while (rows.next()) add(rows.getObject(1, UUID::class.java))
                        } }
                    }
                    execute(connection, "DELETE FROM points WHERE id=?", pointId)
                    RemovedPoint(pointId, formerMemberIds.filter { it != identity.userId })
                }
            }
            val removedPointIds = removedPoints.map { it.pointId }.toSet()
            val membershipMessages = activeChatIds.mapNotNull { chatId ->
                val pointId = pointForChat(connection, chatId) ?: return@mapNotNull null
                if (pointId in removedPointIds) return@mapNotNull null
                val draft = SystemMessageDraft(SystemMessageKind.MEMBER_LEFT, null, pointId, chatId)
                MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), draft), draft)
            }
            val changedIds = (votedProposalIds + closedProposalIds).distinct()
            val proposalViews = projection.readMany(connection, changedIds, identity.userId)
            val meetingChanges = changedIds.mapNotNull { proposalId ->
                val proposal = proposalViews[proposalId] ?: return@mapNotNull null
                val pointId = pointForChat(connection, proposal.chatId) ?: return@mapNotNull null
                if (pointId in removedPointIds) return@mapNotNull null
                val messages = mutableListOf<MeetingEmission>()
                val bookingChange = cancelledBookings.firstOrNull { it.second == proposalId }?.let { (bookingId, _) ->
                    val draft = SystemMessageDraft(SystemMessageKind.MEETING_BOOKING_CANCELLED, null,
                        pointId, proposal.chatId, proposalId = proposalId, bookingId = bookingId,
                        reason = SystemMessageReason.INSUFFICIENT_VOTES)
                    messages += MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), draft), draft)
                    BookingChange(bookingId, "cancelled", "insufficient_votes")
                }
                if (proposalId in closedProposalIds) {
                    val draft = SystemMessageDraft(SystemMessageKind.MEETING_CLOSED, null,
                        pointId, proposal.chatId, proposalId = proposalId,
                        startsAt = proposal.startsAt, endsAt = proposal.endsAt)
                    messages += MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), draft), draft)
                }
                DeletionMeetingChange(proposal, messages, bookingChange)
            }
            for (pointId in pointIds) if (pointId !in removedPointIds) {
                execute(connection, "UPDATE points SET updated_at=clock_timestamp() WHERE id=?", pointId)
            }
            execute(connection, "UPDATE points SET created_by_user_id=NULL WHERE created_by_user_id=?", identity.userId)
            execute(connection, "UPDATE messages SET author_user_id=NULL WHERE author_user_id=?", identity.userId)
            execute(connection,
                """UPDATE messages SET system_payload=jsonb_set(system_payload,'{actorUserId}','null'::jsonb)
                   WHERE type='SYSTEM' AND system_payload->>'actorUserId'=?""", identity.userId.toString())
            execute(connection, "UPDATE meeting_proposals SET proposer_user_id=NULL WHERE proposer_user_id=?", identity.userId)
            execute(connection, "UPDATE bookings SET booked_by_user_id=NULL WHERE booked_by_user_id=?", identity.userId)
            execute(connection,
                "UPDATE reports SET reason_text='Причина скрыта: аккаунт удалён' WHERE reporter_user_id=? OR target_user_id=?",
                identity.userId, identity.userId)

            val fanout = meetingChanges.associateWith { change ->
                notificationRecipients.activeChatMembers(connection, change.proposal.chatId, identity.userId)
            }
            val otherRecipients = fanout.values.flatten().distinct().filter { it != identity.userId }
            // All recipient locks are acquired together, in the same order as every other writer.
            val barrier = RecipientBarrier.acquire(connection,
                otherRecipients.map { RecipientLockClaim(it, RecipientLockMode.SHARED) } +
                    RecipientLockClaim(identity.userId, RecipientLockMode.EXCLUSIVE))
            for ((change, recipients) in fanout) for (recipientId in recipients) {
                val proposal = change.proposal
                val pointId = checkNotNull(pointForChat(connection, proposal.chatId))
                if (proposal.id in votedProposalIds) notificationWriter.write(connection, barrier,
                    NotificationEventDraft(recipientId, NotificationEventType.PROPOSAL_VOTE,
                        "proposal_vote:${proposal.id}:${proposal.revision}",
                        buildJsonObject { put("title", "Голосование по встрече"); put("body", "Изменилось число участников") },
                        pointId = pointId, chatId = proposal.chatId, proposalId = proposal.id))
                change.bookingChange?.let { booking -> notificationWriter.write(connection, barrier,
                    NotificationEventDraft(recipientId, NotificationEventType.BOOKING_CANCELLED,
                        "booking_cancelled:${booking.bookingId}",
                        buildJsonObject { put("title", "Бронь встречи отменена"); put("body", "Недостаточно участников") },
                        pointId = pointId, chatId = proposal.chatId, proposalId = proposal.id,
                        bookingId = booking.bookingId)) }
            }
            // No writer may create a new personal event after this exclusive barrier commits.
            execute(connection, "DELETE FROM notification_events WHERE recipient_user_id=?", identity.userId)
            execute(connection, "DELETE FROM device_registrations WHERE user_id=?", identity.userId)
            execute(connection, "DELETE FROM notification_locations WHERE user_id=?", identity.userId)
            execute(connection, "DELETE FROM notification_settings WHERE user_id=?", identity.userId)
            execute(connection, "DELETE FROM operation_idempotency WHERE actor_user_id=?", identity.userId)
            execute(connection, "DELETE FROM point_creation_events WHERE creator_user_id=?", identity.userId)
            execute(connection, "DELETE FROM message_rate_events WHERE user_id=?", identity.userId)
            if (locked.avatarKey != null) execute(connection,
                """INSERT INTO media_deletion_jobs
                   (id,object_key,status,attempts,next_attempt_at,created_at)
                   VALUES (gen_random_uuid(),?,'PENDING',0,clock_timestamp(),clock_timestamp())""", locked.avatarKey)
            val revokedSessionIds = ids(connection,
                "SELECT id FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL ORDER BY id", identity.userId)
            execute(connection, "UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE user_id=? AND revoked_at IS NULL", identity.userId)
            execute(connection,
                """UPDATE users SET phone_e164=NULL,password_hash=NULL,display_name=NULL,age_years=NULL,
                   avatar_object_key=NULL,accepted_privacy_version=NULL,accepted_privacy_at=NULL,
                   create_point_blocked_until=NULL,message_muted_until=NULL,report_mute_triggered_at=NULL,
                   auth_version=auth_version+1,deleted_at=clock_timestamp(),updated_at=clock_timestamp()
                   WHERE id=?""", identity.userId)
            val finalViews = projection.readMany(connection, meetingChanges.map { it.proposal.id }, identity.userId)
            AccountDeletionResult.Deleted(removedPoints, revokedSessionIds,
                meetingChanges.map { change -> change.copy(proposal = finalViews[change.proposal.id] ?: change.proposal) },
                membershipMessages)
        }
    }

    private data class LockedUser(val hash: String, val authVersion: Int, val avatarKey: String?, val deleted: Boolean)

    private fun activeSession(connection: Connection, identity: SessionIdentity): Boolean = connection.prepareStatement(
        """SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL
           AND expires_at>clock_timestamp()"""
    ).use { statement ->
        statement.setObject(1, identity.sessionId); statement.setObject(2, identity.userId)
        statement.executeQuery().use { it.next() }
    }

    private fun lock(connection: Connection, table: String, id: UUID) {
        check(table in setOf("points", "chats", "meeting_proposals"))
        connection.prepareStatement("SELECT id FROM $table WHERE id=? FOR UPDATE").use { statement ->
            statement.setObject(1, id); statement.executeQuery().use { it.next() }
        }
    }

    private fun pointForChat(connection: Connection, chatId: UUID): UUID? = connection.prepareStatement(
        """SELECT pt.point_id FROM chats c JOIN point_topics pt ON pt.id=c.point_topic_id WHERE c.id=?"""
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
    }

    private fun updateReturningIds(connection: Connection, sql: String, userId: UUID): List<UUID> =
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getObject(1, UUID::class.java))
            } }
        }

    private fun ids(connection: Connection, sql: String, userId: UUID, pointIds: List<UUID>? = null,
                    repeatUserId: Int = 1): List<UUID> =
        connection.prepareStatement(sql).use { statement ->
            for (index in 1..repeatUserId) statement.setObject(index, userId)
            val array = pointIds?.let { connection.createArrayOf("uuid", it.toTypedArray()) }
            if (array != null) statement.setArray(repeatUserId + 1, array)
            try {
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            } finally { array?.free() }
        }

    private fun ids(connection: Connection, sql: String, pointIds: List<UUID>): List<UUID> =
        connection.prepareStatement(sql).use { statement ->
            val array = connection.createArrayOf("uuid", pointIds.toTypedArray())
            try {
                statement.setArray(1, array)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java))
                } }
            } finally { array.free() }
        }

    private fun execute(connection: Connection, sql: String, vararg args: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    private fun execute(connection: Connection, sql: String, ids: List<UUID>): Int {
        val array = connection.createArrayOf("uuid", ids.toTypedArray())
        return try { execute(connection, sql, array) } finally { array.free() }
    }
}
