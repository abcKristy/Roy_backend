package com.example.meetings

import com.example.chats.ChatCursorCodec
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
import com.example.persistence.SystemMessageDraft
import com.example.persistence.SystemMessageKind
import com.example.persistence.SystemMessageReason
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Proposal and vote operations use actor -> point -> proposal -> chat -> recipient locks. */
class ProposalService(
    private val database: DatabaseRuntime,
    cursorSecret: String,
    private val idempotency: JdbcIdempotencyOperations = JdbcIdempotencyOperations(),
    private val projection: MeetingProjection = MeetingProjection(),
    private val systemMessages: JdbcSystemMessageWriter = JdbcSystemMessageWriter(),
    private val recipients: JdbcNotificationRecipients = JdbcNotificationRecipients(),
    private val notifications: JdbcNotificationWriter = JdbcNotificationWriter(),
    // Testable creation time, sampled after locks; production retains PostgreSQL wall time.
    private val clock: Clock? = null,
    private val cancellationPolicy: BookingCancellationPolicy = BookingCancellationPolicy(),
) {
    private val cursors = ChatCursorCodec(cursorSecret)

    suspend fun list(chatId: UUID, viewerId: UUID, cursor: String? = null, limit: Int = 30): PageView<ProposalView>? {
        require(limit in 1..100)
        val fingerprint = fingerprint("proposals:$viewerId:$chatId:$limit")
        val after = cursor?.let { cursors.decode("proposals", fingerprint, it) }
        require(after?.third == null) { "Invalid cursor" }
        return database.withReadOnlyJdbcTransaction { connection ->
            if (!isActiveMember(connection, chatId, viewerId)) return@withReadOnlyJdbcTransaction null
            val heads = connection.prepareStatement(
                """SELECT id,created_at FROM meeting_proposals
                   WHERE chat_id=? AND (?::timestamptz IS NULL OR (created_at,id)<(?,?))
                   ORDER BY created_at DESC,id DESC LIMIT ?"""
            ).use { statement ->
                statement.setObject(1, chatId)
                val at = after?.first?.atOffset(ZoneOffset.UTC)
                statement.setObject(2, at)
                statement.setObject(3, at)
                statement.setObject(4, after?.second)
                statement.setInt(5, limit + 1)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getObject(2, OffsetDateTime::class.java).toInstant())
                } }
            }
            val page = heads.take(limit)
            val projected = projection.readMany(connection, page.map { it.first }, viewerId)
            PageView(page.mapNotNull { projected[it.first] },
                if (heads.size > limit) page.lastOrNull()?.let { cursors.encode("proposals", fingerprint, it.second, it.first) }
                else null)
        }
    }

    suspend fun detail(proposalId: UUID, viewerId: UUID): ProposalView? = database.withReadOnlyJdbcTransaction { connection ->
        val chatId = chatForProposal(connection, proposalId) ?: return@withReadOnlyJdbcTransaction null
        if (!isActiveMember(connection, chatId, viewerId)) return@withReadOnlyJdbcTransaction null
        projection.read(connection, proposalId, viewerId)
    }

    suspend fun votes(proposalId: UUID, viewerId: UUID, cursor: String? = null, limit: Int = 30): PageView<VoteView>? {
        require(limit in 1..100)
        val fingerprint = fingerprint("votes:$viewerId:$proposalId:$limit")
        val after = cursor?.let { cursors.decode("votes", fingerprint, it) }
        require(after?.third == null) { "Invalid cursor" }
        return database.withReadOnlyJdbcTransaction { connection ->
            val chatId = chatForProposal(connection, proposalId) ?: return@withReadOnlyJdbcTransaction null
            if (!isActiveMember(connection, chatId, viewerId)) return@withReadOnlyJdbcTransaction null
            val rows = connection.prepareStatement(
                """SELECT v.user_id,u.display_name,u.avatar_object_key,v.created_at
                   FROM votes AS v JOIN users AS u ON u.id=v.user_id
                   WHERE v.proposal_id=? AND (?::timestamptz IS NULL OR (v.created_at,v.user_id)<(?,?))
                   ORDER BY v.created_at DESC,v.user_id DESC LIMIT ?"""
            ).use { statement ->
                statement.setObject(1, proposalId)
                val at = after?.first?.atOffset(ZoneOffset.UTC)
                statement.setObject(2, at)
                statement.setObject(3, at)
                statement.setObject(4, after?.second)
                statement.setInt(5, limit + 1)
                statement.executeQuery().use { results -> buildList {
                    while (results.next()) add(VoteView(proposalId, results.getObject(1, UUID::class.java),
                        results.getString(2), results.getString(3), results.getObject(4, OffsetDateTime::class.java).toInstant()))
                } }
            }
            val page = rows.take(limit)
            PageView(page, if (rows.size > limit) page.lastOrNull()?.let {
                cursors.encode("votes", fingerprint, it.createdAt, it.userId)
            } else null)
        }
    }

    suspend fun create(command: CreateProposalCommand,
                       successBody: (Connection, ProposalView) -> JsonObject): CreateProposalResult {
        if (!command.endsAt.isAfter(command.startsAt)) return CreateProposalResult.InvalidInterval
        // Validate persisted resolution, retaining the existing raw-Instant replay hash.
        val startsAt = command.startsAt.truncatedTo(ChronoUnit.MICROS)
        val endsAt = command.endsAt.truncatedTo(ChronoUnit.MICROS)
        val hash = JdbcIdempotencyOperations.requestHash("create_proposal", listOf(command.chatId),
            buildJsonObject {
                put("startsAt", command.startsAt.toString())
                put("endsAt", command.endsAt.toString())
            })
        return database.withRetryingJdbcTransaction { connection ->
            when (val decision = idempotency.lockActorAndCheck(connection, command.actorId,
                "create_proposal", command.idempotencyKey, hash)) {
                is JdbcIdempotencyOperations.Decision.Replay ->
                    return@withRetryingJdbcTransaction CreateProposalResult.Replay(checkNotNull(decision.record.responseSnapshot))
                JdbcIdempotencyOperations.Decision.KeyReused ->
                    return@withRetryingJdbcTransaction CreateProposalResult.KeyReused
                JdbcIdempotencyOperations.Decision.New -> Unit
            }
            // Existing successful requests must replay before applying stricter storage bounds.
            if (!endsAt.isAfter(startsAt)) return@withRetryingJdbcTransaction CreateProposalResult.InvalidInterval
            val pointId = pointForChat(connection, command.chatId)
                ?: return@withRetryingJdbcTransaction CreateProposalResult.ChatGone
            if (!lockPoint(connection, pointId) || !lockChat(connection, command.chatId)) {
                return@withRetryingJdbcTransaction CreateProposalResult.ChatGone
            }
            if (!isActiveMember(connection, command.chatId, command.actorId)) {
                return@withRetryingJdbcTransaction CreateProposalResult.NotMember
            }
            val now = clock?.instant() ?: databaseNow(connection)
            if (!startsAt.isAfter(now)) return@withRetryingJdbcTransaction CreateProposalResult.InvalidInterval
            val id = UUID.randomUUID()
            connection.prepareStatement(
                """INSERT INTO meeting_proposals
                   (id,chat_id,proposer_user_id,starts_at,ends_at,lifecycle,revision,created_at,updated_at)
                   VALUES (?,?,?,?,?,'OPEN',0,?,?)"""
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, command.chatId)
                statement.setObject(3, command.actorId)
                statement.setObject(4, startsAt.atOffset(ZoneOffset.UTC))
                statement.setObject(5, endsAt.atOffset(ZoneOffset.UTC))
                statement.setObject(6, now.atOffset(ZoneOffset.UTC))
                statement.setObject(7, now.atOffset(ZoneOffset.UTC))
                check(statement.executeUpdate() == 1)
            }
            val draft = SystemMessageDraft(SystemMessageKind.MEETING_PROPOSED, command.actorId,
                pointId, command.chatId, proposalId = id, startsAt = startsAt, endsAt = endsAt)
            val emission = MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), draft), draft)
            val nearbyIds = recipients.nearbyForProposal(connection, id)
            val barrier = RecipientBarrier.acquire(connection, nearbyIds.map { RecipientLockClaim(it, RecipientLockMode.SHARED) })
            nearbyIds.forEach { recipientId ->
                notifications.write(connection, barrier, NotificationEventDraft(
                    recipientId, NotificationEventType.NEARBY_MEETING, "nearby:$id",
                    buildJsonObject {
                        put("title", "Встреча рядом")
                        put("body", "Рядом предложена встреча")
                    }, pointId = pointId, proposalId = id,
                ))
            }
            val view = checkNotNull(projection.read(connection, id, command.actorId))
            val snapshot = buildJsonObject { put("status", 201); put("body", successBody(connection, view)) }
            idempotency.recordSuccess(connection, command.actorId, "create_proposal", command.idempotencyKey,
                hash, id, snapshot)
            CreateProposalResult.Created(view, snapshot, listOf(emission))
        }
    }

    suspend fun vote(command: VoteCommand): VoteResult = database.withRetryingJdbcTransaction { connection ->
        lockActor(connection, command.actorId)
        val target = targetForProposal(connection, command.proposalId)
            ?: return@withRetryingJdbcTransaction VoteResult.ProposalGone
        if (!lockPoint(connection, target.pointId) || !lockProposal(connection, command.proposalId)) {
            return@withRetryingJdbcTransaction VoteResult.ProposalGone
        }
        if (!isActiveMember(connection, target.chatId, command.actorId)) {
            return@withRetryingJdbcTransaction VoteResult.NotMember
        }
        val state = proposalState(connection, command.proposalId)
        if (state.closed) return@withRetryingJdbcTransaction VoteResult.Closed
        if (!state.startsAt.isAfter(databaseNow(connection))) return@withRetryingJdbcTransaction VoteResult.Started
        val before = voteCount(connection, command.proposalId)
        val changed = if (command.going) connection.prepareStatement(
            """INSERT INTO votes (proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())
               ON CONFLICT (proposal_id,user_id) DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, command.proposalId)
            statement.setObject(2, command.actorId)
            statement.executeUpdate() == 1
        } else connection.prepareStatement(
            "DELETE FROM votes WHERE proposal_id=? AND user_id=?"
        ).use { statement ->
            statement.setObject(1, command.proposalId)
            statement.setObject(2, command.actorId)
            statement.executeUpdate() == 1
        }
        if (!changed) return@withRetryingJdbcTransaction VoteResult.Unchanged(
            checkNotNull(projection.read(connection, command.proposalId, command.actorId)))
        val revision = connection.prepareStatement(
            "UPDATE meeting_proposals SET revision=revision+1,updated_at=clock_timestamp() WHERE id=? RETURNING revision"
        ).use { statement ->
            statement.setObject(1, command.proposalId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
        val after = voteCount(connection, command.proposalId)
        val emissions = mutableListOf<MeetingEmission>()
        val becameReady = before <= 1 && after > 1
        if (becameReady) {
            val draft = SystemMessageDraft(SystemMessageKind.MEETING_READY, command.actorId,
                target.pointId, target.chatId, proposalId = command.proposalId,
                startsAt = state.startsAt, endsAt = state.endsAt)
            emissions += MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), draft), draft)
        }
        var bookingChange: BookingChange? = null
        if (after <= 1) {
            val cancelled = cancellationPolicy.cancelInsufficientBookings(connection, listOf(command.proposalId))
                .singleOrNull()?.first
            if (cancelled != null) {
                cancelReminderJobs(connection, cancelled)
                bookingChange = BookingChange(cancelled, "cancelled", "insufficient_votes")
                val draft = SystemMessageDraft(SystemMessageKind.MEETING_BOOKING_CANCELLED, command.actorId,
                    target.pointId, target.chatId, proposalId = command.proposalId,
                    bookingId = cancelled, reason = SystemMessageReason.INSUFFICIENT_VOTES)
                emissions += MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), draft), draft)
            }
        }
        val recipientIds = recipients.activeChatMembers(connection, target.chatId, command.actorId)
        val barrier = RecipientBarrier.acquire(connection, recipientIds.map { RecipientLockClaim(it, RecipientLockMode.SHARED) })
        for (recipientId in recipientIds) {
            notifications.write(connection, barrier, NotificationEventDraft(
                recipientId, NotificationEventType.PROPOSAL_VOTE,
                "proposal_vote:${command.proposalId}:$revision",
                buildJsonObject {
                    put("title", "Голосование по встрече")
                    put("body", "Изменилось число участников")
                }, pointId = target.pointId, chatId = target.chatId, proposalId = command.proposalId,
            ))
            if (becameReady) notifications.write(connection, barrier, NotificationEventDraft(
                recipientId, NotificationEventType.PROPOSAL_READY,
                "proposal_ready:${command.proposalId}:$revision",
                buildJsonObject {
                    put("title", "Встреча готова к брони")
                    put("body", "Достаточно участников")
                }, pointId = target.pointId, chatId = target.chatId, proposalId = command.proposalId,
            ))
            bookingChange?.let { change -> notifications.write(connection, barrier, NotificationEventDraft(
                recipientId, NotificationEventType.BOOKING_CANCELLED,
                "booking_cancelled:${change.bookingId}",
                buildJsonObject {
                    put("title", "Бронь встречи отменена")
                    put("body", "Недостаточно участников")
                }, pointId = target.pointId, chatId = target.chatId, proposalId = command.proposalId,
                bookingId = change.bookingId,
            )) }
        }
        VoteResult.Updated(checkNotNull(projection.read(connection, command.proposalId, command.actorId)),
            emissions, bookingChange)
    }

    private data class ProposalTarget(val pointId: UUID, val chatId: UUID)
    private data class ProposalState(val startsAt: Instant, val endsAt: Instant, val closed: Boolean)

    private fun pointForChat(connection: Connection, chatId: UUID): UUID? = connection.prepareStatement(
        "SELECT pt.point_id FROM chats AS c JOIN point_topics AS pt ON pt.id=c.point_topic_id WHERE c.id=?"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    private fun chatForProposal(connection: Connection, proposalId: UUID): UUID? = connection.prepareStatement(
        "SELECT chat_id FROM meeting_proposals WHERE id=?"
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    private fun targetForProposal(connection: Connection, proposalId: UUID): ProposalTarget? = connection.prepareStatement(
        """SELECT pt.point_id,p.chat_id FROM meeting_proposals AS p
           JOIN chats AS c ON c.id=p.chat_id JOIN point_topics AS pt ON pt.id=c.point_topic_id WHERE p.id=?"""
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.executeQuery().use { rows -> if (rows.next()) ProposalTarget(
            rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java)) else null }
    }

    private fun proposalState(connection: Connection, proposalId: UUID): ProposalState = connection.prepareStatement(
        "SELECT starts_at,ends_at,lifecycle FROM meeting_proposals WHERE id=?"
    ).use { statement ->
        statement.setObject(1, proposalId)
        statement.executeQuery().use { rows ->
            check(rows.next())
            ProposalState(rows.getObject(1, OffsetDateTime::class.java).toInstant(),
                rows.getObject(2, OffsetDateTime::class.java).toInstant(), rows.getString(3) == "CLOSED")
        }
    }

    private fun isActiveMember(connection: Connection, chatId: UUID, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.setObject(2, userId)
        statement.executeQuery().use { it.next() }
    }

    private fun lockActor(connection: Connection, actorId: UUID) {
        connection.prepareStatement("SELECT 1 FROM users WHERE id=? AND deleted_at IS NULL FOR NO KEY UPDATE").use { statement ->
            statement.setObject(1, actorId)
            statement.executeQuery().use { check(it.next()) { "Active user required" } }
        }
    }

    private fun lockPoint(connection: Connection, pointId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM points WHERE id=? FOR UPDATE"
    ).use { statement -> statement.setObject(1, pointId); statement.executeQuery().use { it.next() } }

    private fun lockChat(connection: Connection, chatId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM chats WHERE id=? FOR UPDATE"
    ).use { statement -> statement.setObject(1, chatId); statement.executeQuery().use { it.next() } }

    private fun lockProposal(connection: Connection, proposalId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM meeting_proposals WHERE id=? FOR UPDATE"
    ).use { statement -> statement.setObject(1, proposalId); statement.executeQuery().use { it.next() } }

    private fun voteCount(connection: Connection, proposalId: UUID): Int = connection.prepareStatement(
        "SELECT count(*) FROM votes WHERE proposal_id=?"
    ).use { statement -> statement.setObject(1, proposalId); statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) } }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }

    private fun cancelReminderJobs(connection: Connection, bookingId: UUID) {
        connection.prepareStatement(
            """UPDATE push_delivery_jobs AS job SET status='CANCELLED',claim_token=NULL,lease_until=NULL,
               sent_at=NULL,completed_at=clock_timestamp() WHERE job.status IN ('PENDING','PROCESSING')
               AND EXISTS (SELECT 1 FROM notification_events AS event
                           WHERE event.id=job.notification_event_id AND event.booking_id=?
                             AND event.event_type='reminder_1h')"""
        ).use { statement -> statement.setObject(1, bookingId); statement.executeUpdate() }
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
