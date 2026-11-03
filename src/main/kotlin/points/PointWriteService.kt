package com.example.points

import com.example.persistence.AdvisoryLockKeys
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
import com.example.persistence.acquireTransactionAdvisoryLock
import com.example.persistence.withRetryingJdbcTransaction
import com.example.meetings.MeetingEmission
import com.example.meetings.MeetingProjection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.roundToInt

/** Point mutations run in one caller-owned transaction; external I/O belongs before the call. */
class PointWriteService(
    private val database: DatabaseRuntime,
    private val idempotency: JdbcIdempotencyOperations = JdbcIdempotencyOperations(),
    private val systemMessages: JdbcSystemMessageWriter = JdbcSystemMessageWriter(),
    private val recipients: JdbcNotificationRecipients = JdbcNotificationRecipients(),
    private val notifications: JdbcNotificationWriter = JdbcNotificationWriter(),
    private val meetingProjection: MeetingProjection = MeetingProjection(),
) {
    /** The body callback must only read this Connection and build a deterministic HTTP response body. */
    suspend fun create(
        command: CreatePointCommand,
        successBody: (Connection, CreatedPoint) -> JsonObject,
        quotaErrorBody: (Instant) -> JsonObject,
    ): CreatePointResult {
        val input = command.normalized()
        val topic = input.topic
        val hash = JdbcIdempotencyOperations.requestHash("create_point", emptyList(), buildJsonObject {
            put("lat", input.position.latitude)
            put("lon", input.position.longitude)
            put("address", input.address)
            put("addressSource", input.addressSource.name)
            put("presetKey", topic.presetKey)
            topic.customLabel?.let { put("customLabel", it) }
        })
        return database.withRetryingJdbcTransaction { connection ->
            connection.acquireTransactionAdvisoryLock(AdvisoryLockKeys.pointCreation)
            when (val decision = idempotency.lockActorAndCheck(
                connection, command.actorId, "create_point", command.idempotencyKey, hash,
            )) {
                is JdbcIdempotencyOperations.Decision.Replay ->
                    return@withRetryingJdbcTransaction CreatePointResult.Replay(checkNotNull(decision.record.responseSnapshot))
                JdbcIdempotencyOperations.Decision.KeyReused ->
                    return@withRetryingJdbcTransaction CreatePointResult.KeyReused
                JdbcIdempotencyOperations.Decision.New -> Unit
            }
            val now = databaseNow(connection)
            val quota = PointQuotaSql.read(connection, command.actorId, now)
            quota.blockedUntil?.let {
                return@withRetryingJdbcTransaction CreatePointResult.QuotaBlocked(it)
            }
            if (quota.weekRemaining == 0 || quota.monthRemaining == 0) {
                var response: JsonObject? = null
                val until = idempotency.recordQuotaExceeded(
                    connection, command.actorId, "create_point", command.idempotencyKey, hash,
                ) { blocked ->
                    replaySnapshot(409, quotaErrorBody(blocked)).also { response = it }
                }
                return@withRetryingJdbcTransaction CreatePointResult.QuotaExceeded(until, checkNotNull(response))
            }
            val nearby = nearby(connection, input.position)
            if (nearby.isNotEmpty() && !input.confirmNearby) {
                return@withRetryingJdbcTransaction CreatePointResult.NearbyConfirmationRequired(nearby)
            }
            requirePreset(connection, topic.presetKey)
            val pointId = UUID.randomUUID()
            val topicId = UUID.randomUUID()
            val chatId = UUID.randomUUID()
            insertPoint(connection, pointId, input, now)
            insertTopicAndChat(connection, pointId, topicId, chatId, topic, now)
            insertMembership(connection, chatId, command.actorId, now)
            connection.prepareStatement(
                "INSERT INTO point_creation_events (id,creator_user_id,point_id,created_at) VALUES (?,?,?,?)"
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, command.actorId)
                statement.setObject(3, pointId)
                statement.setObject(4, now.atOffset(ZoneOffset.UTC))
                check(statement.executeUpdate() == 1)
            }
            val message = systemMessages.append(connection, UUID.randomUUID(),
                SystemMessageDraft(SystemMessageKind.MEMBER_JOINED, command.actorId, pointId, chatId))
            updateReadCursor(connection, chatId, command.actorId, message.messageSeq)
            touchPoint(connection, pointId)
            val created = CreatedPoint(pointId, topicId, chatId, command.actorId, now)
            val snapshot = replaySnapshot(201, successBody(connection, created))
            idempotency.recordSuccess(connection, command.actorId, "create_point", command.idempotencyKey,
                hash, pointId, snapshot)
            CreatePointResult.Created(created, snapshot)
        }
    }

    /** Adds a new topic/chat and joins its creator in the same transaction. */
    suspend fun addTopic(
        command: AddTopicCommand,
        successBody: (Connection, CreatedTopic) -> JsonObject,
    ): AddTopicResult {
        val topic = normalized(command.topic)
        val hash = JdbcIdempotencyOperations.requestHash("add_topic", listOf(command.pointId), buildJsonObject {
            put("presetKey", topic.presetKey)
            topic.customLabel?.let { put("customLabel", it) }
        })
        return try {
            database.withRetryingJdbcTransaction { connection ->
                when (val decision = idempotency.lockActorAndCheck(
                    connection, command.actorId, "add_topic", command.idempotencyKey, hash,
                )) {
                    is JdbcIdempotencyOperations.Decision.Replay ->
                        return@withRetryingJdbcTransaction AddTopicResult.Replay(checkNotNull(decision.record.responseSnapshot))
                    JdbcIdempotencyOperations.Decision.KeyReused ->
                        return@withRetryingJdbcTransaction AddTopicResult.KeyReused
                    JdbcIdempotencyOperations.Decision.New -> Unit
                }
                if (!lockPoint(connection, command.pointId)) return@withRetryingJdbcTransaction AddTopicResult.PointGone
                requirePreset(connection, topic.presetKey)
                if (topicExists(connection, command.pointId, topic)) return@withRetryingJdbcTransaction AddTopicResult.DuplicateTopic
                val now = databaseNow(connection)
                val topicId = UUID.randomUUID()
                val chatId = UUID.randomUUID()
                insertTopicAndChat(connection, command.pointId, topicId, chatId, topic, now)
                insertMembership(connection, chatId, command.actorId, now)
                val message = systemMessages.append(connection, UUID.randomUUID(),
                    SystemMessageDraft(SystemMessageKind.MEMBER_JOINED, command.actorId, command.pointId, chatId))
                updateReadCursor(connection, chatId, command.actorId, message.messageSeq)
                touchPoint(connection, command.pointId)
                val created = CreatedTopic(command.pointId, topicId, chatId, command.actorId, now)
                val snapshot = replaySnapshot(201, successBody(connection, created))
                idempotency.recordSuccess(connection, command.actorId, "add_topic", command.idempotencyKey,
                    hash, topicId, snapshot)
                AddTopicResult.Created(created, snapshot)
            }
        } catch (error: PSQLException) {
            if (error.sqlState == "23505" &&
                error.serverErrorMessage?.constraint == "point_topics_normalized_unique") {
                AddTopicResult.DuplicateTopic
            } else {
                throw error
            }
        }
    }

    suspend fun join(userId: UUID, chatId: UUID): JoinPointChatResult = database.withRetryingJdbcTransaction { connection ->
        lockUser(connection, userId)
        val pointId = pointIdForChat(connection, chatId) ?: return@withRetryingJdbcTransaction JoinPointChatResult.ChatGone
        if (!lockPoint(connection, pointId) || !lockChat(connection, chatId)) {
            return@withRetryingJdbcTransaction JoinPointChatResult.ChatGone
        }
        readMembership(connection, chatId, userId)?.takeIf { it.leftAt == null }?.let {
            return@withRetryingJdbcTransaction JoinPointChatResult.AlreadyMember(it)
        }
        val joinedAt = databaseNow(connection)
        connection.prepareStatement(
            """INSERT INTO chat_memberships (chat_id,user_id,joined_at,last_read_seq)
               VALUES (?,?,?,0)
               ON CONFLICT (chat_id,user_id) DO UPDATE
               SET joined_at = EXCLUDED.joined_at, left_at = NULL"""
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.setObject(2, userId)
            statement.setObject(3, joinedAt.atOffset(ZoneOffset.UTC))
            check(statement.executeUpdate() == 1)
        }
        val message = systemMessages.append(connection, UUID.randomUUID(),
            SystemMessageDraft(SystemMessageKind.MEMBER_JOINED, userId, pointId, chatId))
        updateReadCursor(connection, chatId, userId, message.messageSeq)
        touchPoint(connection, pointId)
        JoinPointChatResult.Joined(PointMembership(chatId, userId, joinedAt, null, message.messageSeq))
    }

    /** Cancellation is fully transactional; public leave routing must wait for meeting use cases. */
    suspend fun leave(userId: UUID, chatId: UUID): LeavePointChatResult = database.withRetryingJdbcTransaction { connection ->
        lockUser(connection, userId)
        val pointId = pointIdForChat(connection, chatId) ?: return@withRetryingJdbcTransaction LeavePointChatResult.ChatGone
        if (!lockPoint(connection, pointId) || !lockChat(connection, chatId)) {
            return@withRetryingJdbcTransaction LeavePointChatResult.ChatGone
        }
        val membership = readMembership(connection, chatId, userId)
            ?: return@withRetryingJdbcTransaction LeavePointChatResult.AlreadyLeft
        if (membership.leftAt != null) return@withRetryingJdbcTransaction LeavePointChatResult.AlreadyLeft

        val proposalIds = connection.prepareStatement(
            "SELECT id FROM meeting_proposals WHERE chat_id = ? ORDER BY id FOR UPDATE"
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getObject(1, UUID::class.java))
            } }
        }
        val cancelledBookings = mutableListOf<Pair<UUID, UUID>>() // booking ID, proposal ID
        val changedProposalIds = mutableListOf<UUID>()
        for (proposalId in proposalIds) {
            val removedVote = connection.prepareStatement(
                "DELETE FROM votes WHERE proposal_id = ? AND user_id = ?"
            ).use { statement ->
                statement.setObject(1, proposalId)
                statement.setObject(2, userId)
                statement.executeUpdate() == 1
            }
            if (!removedVote) continue
            changedProposalIds += proposalId
            connection.prepareStatement(
                "UPDATE meeting_proposals SET revision = revision + 1,updated_at = clock_timestamp() WHERE id = ?"
            ).use { statement -> statement.setObject(1, proposalId); check(statement.executeUpdate() == 1) }
            val voteCount = connection.prepareStatement("SELECT count(*) FROM votes WHERE proposal_id = ?").use { statement ->
                statement.setObject(1, proposalId)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
            }
            if (voteCount <= 1) {
                connection.prepareStatement(
                    """UPDATE bookings AS b SET status='CANCELLED',cancelled_at=clock_timestamp(),
                       cancel_reason='INSUFFICIENT_VOTES'
                       FROM meeting_proposals AS p
                       WHERE b.proposal_id = p.id AND p.id = ? AND p.starts_at > clock_timestamp()
                         AND b.status='ACTIVE' RETURNING b.id"""
                ).use { statement ->
                    statement.setObject(1, proposalId)
                    statement.executeQuery().use { rows -> if (rows.next()) cancelledBookings += rows.getObject(1, UUID::class.java) to proposalId }
                }
            }
        }
        val leftAt = databaseNow(connection)
        connection.prepareStatement("UPDATE chat_memberships SET left_at = ? WHERE chat_id = ? AND user_id = ?").use { statement ->
            statement.setObject(1, leftAt.atOffset(ZoneOffset.UTC))
            statement.setObject(2, chatId)
            statement.setObject(3, userId)
            check(statement.executeUpdate() == 1)
        }
        val emissions = mutableListOf<MeetingEmission>()
        for ((bookingId, proposalId) in cancelledBookings) {
            cancelReminderJobs(connection, bookingId)
            val draft = SystemMessageDraft(
                SystemMessageKind.MEETING_BOOKING_CANCELLED, userId, pointId, chatId,
                proposalId = proposalId, bookingId = bookingId,
                reason = SystemMessageReason.INSUFFICIENT_VOTES,
            )
            emissions += MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), draft), draft)
        }
        val leftDraft = SystemMessageDraft(SystemMessageKind.MEMBER_LEFT, userId, pointId, chatId)
        emissions += MeetingEmission(systemMessages.append(connection, UUID.randomUUID(), leftDraft), leftDraft)
        touchPoint(connection, pointId)
        val changedProposals = changedProposalIds.mapNotNull { meetingProjection.read(connection, it, userId) }
        val remaining = connection.prepareStatement(
            """SELECT count(*) FROM chat_memberships AS m
               JOIN chats AS c ON c.id = m.chat_id
               JOIN point_topics AS t ON t.id = c.point_topic_id
               WHERE t.point_id = ? AND m.left_at IS NULL"""
        ).use { statement ->
            statement.setObject(1, pointId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
        val deletedRecipients = if (remaining == 0) connection.prepareStatement(
            """SELECT DISTINCT m.user_id FROM chat_memberships AS m
               JOIN chats AS c ON c.id=m.chat_id
               JOIN point_topics AS t ON t.id=c.point_topic_id
               WHERE t.point_id=?"""
        ).use { statement ->
            statement.setObject(1, pointId)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getObject(1, UUID::class.java))
            } }
        } else emptyList()
        if (remaining == 0) {
            connection.prepareStatement("DELETE FROM points WHERE id = ?").use { statement ->
                statement.setObject(1, pointId)
                check(statement.executeUpdate() == 1)
            }
        } else if (cancelledBookings.isNotEmpty()) {
            val recipientIds = recipients.activeChatMembers(connection, chatId, userId)
            val barrier = RecipientBarrier.acquire(connection, recipientIds.map { RecipientLockClaim(it, RecipientLockMode.SHARED) })
            for ((bookingId, proposalId) in cancelledBookings) for (recipientId in recipientIds) {
                notifications.write(connection, barrier, NotificationEventDraft(
                    recipientUserId = recipientId,
                    eventType = NotificationEventType.BOOKING_CANCELLED,
                    dedupeKey = "booking_cancelled:$bookingId",
                    displaySnapshot = buildJsonObject {
                        put("title", "Бронь встречи отменена")
                        put("body", "Недостаточно участников")
                    },
                    pointId = pointId, chatId = chatId, proposalId = proposalId, bookingId = bookingId,
                ))
            }
        }
        LeavePointChatResult.Left(pointId, remaining == 0, deletedRecipients, changedProposals,
            cancelledBookings.map { PointBookingChange(it.second, it.first, "cancelled", "insufficient_votes") }, emissions)
    }

    private fun normalized(topic: PointTopicInput): PointTopicInput {
        require(topic.presetKey.isNotBlank())
        val label = topic.customLabel?.trim()
        require(if (topic.presetKey == "other") !label.isNullOrBlank() && label.codePointCount(0, label.length) <= 40 else label == null)
        return PointTopicInput(topic.presetKey, label)
    }

    private fun replaySnapshot(status: Int, body: JsonObject): JsonObject = buildJsonObject {
        put("status", status)
        put("body", body)
    }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next())
            rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }

    private fun nearby(connection: Connection, position: PointPosition): List<NearbyPoint> = connection.prepareStatement(
        """SELECT id,address,ST_Y(location),ST_X(location),ST_Distance(location::geography,
             ST_SetSRID(ST_MakePoint(?,?),4326)::geography) AS meters
           FROM points WHERE ST_DWithin(location::geography,
             ST_SetSRID(ST_MakePoint(?,?),4326)::geography,100)
           ORDER BY meters,id LIMIT 20"""
    ).use { statement ->
        statement.setDouble(1, position.longitude)
        statement.setDouble(2, position.latitude)
        statement.setDouble(3, position.longitude)
        statement.setDouble(4, position.latitude)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(NearbyPoint(
                rows.getObject(1, UUID::class.java),
                rows.getDouble(3), rows.getDouble(4), rows.getString(2), rows.getDouble(5).roundToInt(),
            ))
        } }
    }

    private fun requirePreset(connection: Connection, key: String) {
        connection.prepareStatement("SELECT 1 FROM topic_presets WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows -> require(rows.next()) { "Unknown topic preset" } }
        }
    }

    private fun topicExists(connection: Connection, pointId: UUID, topic: PointTopicInput): Boolean = connection.prepareStatement(
        """SELECT 1 FROM point_topics WHERE point_id=? AND preset_key=?
           AND COALESCE(lower(btrim(custom_label)),'') = COALESCE(lower(btrim(?::text)),'')"""
    ).use { statement ->
        statement.setObject(1, pointId)
        statement.setString(2, topic.presetKey)
        statement.setString(3, topic.customLabel)
        statement.executeQuery().use { it.next() }
    }

    private fun insertPoint(connection: Connection, pointId: UUID, command: CreatePointCommand, at: Instant) {
        connection.prepareStatement(
            """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
               VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?,?,?,?)"""
        ).use { statement ->
            statement.setObject(1, pointId)
            statement.setDouble(2, command.position.longitude)
            statement.setDouble(3, command.position.latitude)
            statement.setString(4, command.address.trim())
            statement.setString(5, command.addressSource.name)
            statement.setObject(6, command.actorId)
            statement.setObject(7, at.atOffset(ZoneOffset.UTC))
            statement.setObject(8, at.atOffset(ZoneOffset.UTC))
            check(statement.executeUpdate() == 1)
        }
    }

    private fun insertTopicAndChat(connection: Connection, pointId: UUID, topicId: UUID, chatId: UUID,
                                   topic: PointTopicInput, at: Instant) {
        connection.prepareStatement(
            "INSERT INTO point_topics (id,point_id,preset_key,custom_label,created_at) VALUES (?,?,?,?,?)"
        ).use { statement ->
            statement.setObject(1, topicId)
            statement.setObject(2, pointId)
            statement.setString(3, topic.presetKey)
            statement.setString(4, topic.customLabel)
            statement.setObject(5, at.atOffset(ZoneOffset.UTC))
            check(statement.executeUpdate() == 1)
        }
        connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,?)").use { statement ->
            statement.setObject(1, chatId)
            statement.setObject(2, topicId)
            statement.setObject(3, at.atOffset(ZoneOffset.UTC))
            check(statement.executeUpdate() == 1)
        }
    }

    private fun insertMembership(connection: Connection, chatId: UUID, userId: UUID, at: Instant) {
        connection.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,?)").use { statement ->
            statement.setObject(1, chatId)
            statement.setObject(2, userId)
            statement.setObject(3, at.atOffset(ZoneOffset.UTC))
            check(statement.executeUpdate() == 1)
        }
    }

    private fun updateReadCursor(connection: Connection, chatId: UUID, userId: UUID, seq: Long) {
        connection.prepareStatement("UPDATE chat_memberships SET last_read_seq = ? WHERE chat_id = ? AND user_id = ?").use { statement ->
            statement.setLong(1, seq)
            statement.setObject(2, chatId)
            statement.setObject(3, userId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun touchPoint(connection: Connection, pointId: UUID) {
        connection.prepareStatement("UPDATE points SET updated_at=clock_timestamp() WHERE id=?").use { statement ->
            statement.setObject(1, pointId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun lockUser(connection: Connection, userId: UUID) {
        connection.prepareStatement("SELECT id FROM users WHERE id=? AND deleted_at IS NULL FOR NO KEY UPDATE").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rows -> check(rows.next()) { "Active user required" } }
        }
    }

    private fun pointIdForChat(connection: Connection, chatId: UUID): UUID? = connection.prepareStatement(
        """SELECT t.point_id FROM chats AS c JOIN point_topics AS t ON t.id=c.point_topic_id WHERE c.id=?"""
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    private fun lockPoint(connection: Connection, pointId: UUID): Boolean = connection.prepareStatement(
        "SELECT id FROM points WHERE id=? FOR UPDATE"
    ).use { statement ->
        statement.setObject(1, pointId)
        statement.executeQuery().use { it.next() }
    }

    private fun lockChat(connection: Connection, chatId: UUID): Boolean = connection.prepareStatement(
        "SELECT id FROM chats WHERE id=? FOR UPDATE"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { it.next() }
    }

    private fun readMembership(connection: Connection, chatId: UUID, userId: UUID): PointMembership? = connection.prepareStatement(
        "SELECT joined_at,left_at,last_read_seq FROM chat_memberships WHERE chat_id=? AND user_id=?"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.setObject(2, userId)
        statement.executeQuery().use { rows -> if (rows.next()) PointMembership(chatId,userId,
            rows.getObject(1, OffsetDateTime::class.java).toInstant(),
            rows.getObject(2, OffsetDateTime::class.java)?.toInstant(), rows.getLong(3)) else null }
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
}
