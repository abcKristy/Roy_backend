package com.example.chats

import com.example.persistence.DatabaseRuntime
import com.example.persistence.JdbcIdempotencyOperations
import com.example.persistence.JdbcNotificationRecipients
import com.example.persistence.JdbcNotificationWriter
import com.example.persistence.NotificationEventDraft
import com.example.persistence.NotificationEventType
import com.example.persistence.RecipientBarrier
import com.example.persistence.RecipientLockClaim
import com.example.persistence.RecipientLockMode
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One TEXT message, rate event, read cursor, notification events and replay snapshot commit together. */
class ChatSendService(
    private val database: DatabaseRuntime,
    private val contentPolicy: MessageContentPolicy,
    private val idempotency: JdbcIdempotencyOperations = JdbcIdempotencyOperations(),
    private val recipients: JdbcNotificationRecipients = JdbcNotificationRecipients(),
    private val notifications: JdbcNotificationWriter = JdbcNotificationWriter(),
) {
    suspend fun send(command: SendMessageCommand, successBody: (Connection, MessageView) -> JsonObject): SendMessageResult {
        val text = command.text.trim()
        if (text.isEmpty() || text.codePointCount(0, text.length) > 4096) return SendMessageResult.InvalidText
        val hash = JdbcIdempotencyOperations.requestHash("send_message", listOf(command.chatId),
            buildJsonObject { put("text", text) })
        return database.withRetryingJdbcTransaction { connection ->
            val actor = lockActor(connection, command.actorId)
            when (val decision = idempotency.lockActorAndCheck(connection, command.actorId,
                "send_message", command.idempotencyKey, hash)) {
                is JdbcIdempotencyOperations.Decision.Replay ->
                    return@withRetryingJdbcTransaction SendMessageResult.Replay(checkNotNull(decision.record.responseSnapshot))
                JdbcIdempotencyOperations.Decision.KeyReused ->
                    return@withRetryingJdbcTransaction SendMessageResult.KeyReused
                JdbcIdempotencyOperations.Decision.New -> Unit
            }
            if (!activeMembership(connection, command.chatId, command.actorId)) {
                return@withRetryingJdbcTransaction SendMessageResult.NotMember
            }
            val checkedAt = databaseNow(connection)
            actor.mutedUntil?.takeIf { it.isAfter(checkedAt) }?.let {
                return@withRetryingJdbcTransaction SendMessageResult.Muted(it)
            }
            floodDelay(connection, command.actorId, checkedAt)?.let {
                return@withRetryingJdbcTransaction SendMessageResult.Flood(it)
            }
            if (contentPolicy.rejected(text)) return@withRetryingJdbcTransaction SendMessageResult.RejectedPattern
            val pointId = pointId(connection, command.chatId)
                ?: return@withRetryingJdbcTransaction SendMessageResult.NotMember
            if (!lockPoint(connection, pointId)) return@withRetryingJdbcTransaction SendMessageResult.NotMember
            if (!lockChat(connection, command.chatId)) return@withRetryingJdbcTransaction SendMessageResult.NotMember
            if (!activeMembership(connection, command.chatId, command.actorId)) {
                return@withRetryingJdbcTransaction SendMessageResult.NotMember
            }
            val seq = nextSeq(connection, command.chatId)
            val now = databaseNow(connection)
            val id = UUID.randomUUID()
            connection.prepareStatement(
                """INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at)
                   VALUES (?,?,?,?,'TEXT',?,?)"""
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, command.chatId)
                statement.setLong(3, seq)
                statement.setObject(4, command.actorId)
                statement.setString(5, text)
                statement.setObject(6, now.atOffset(ZoneOffset.UTC))
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement(
                "INSERT INTO message_rate_events (id,user_id,accepted_at) VALUES (?,?,?)"
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, command.actorId)
                statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement(
                "UPDATE chat_memberships SET last_read_seq=? WHERE chat_id=? AND user_id=? AND left_at IS NULL"
            ).use { statement ->
                statement.setLong(1, seq)
                statement.setObject(2, command.chatId)
                statement.setObject(3, command.actorId)
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement("UPDATE points SET updated_at=? WHERE id=?").use { statement ->
                statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                statement.setObject(2, pointId)
                check(statement.executeUpdate() == 1)
            }
            val message = MessageView(id, command.chatId, seq, "TEXT", command.actorId,
                actor.displayName, actor.avatarObjectKey, text, null, null, now)
            val recipientIds = recipients.activeChatMembers(connection, command.chatId, command.actorId)
            val barrier = RecipientBarrier.acquire(connection, recipientIds.map {
                RecipientLockClaim(it, RecipientLockMode.SHARED)
            })
            for (recipientId in recipientIds) {
                notifications.write(connection, barrier, NotificationEventDraft(
                    recipientId, NotificationEventType.CHAT_MESSAGE, "chat_message:$id",
                    buildJsonObject {
                        put("title", "Новое сообщение")
                        put("body", "В чате появилось сообщение")
                    }, pointId = pointId, chatId = command.chatId,
                ))
            }
            val snapshot = buildJsonObject { put("status", 201); put("body", successBody(connection, message)) }
            idempotency.recordSuccess(connection, command.actorId, "send_message", command.idempotencyKey,
                hash, id, snapshot)
            SendMessageResult.Created(message, snapshot)
        }
    }

    private data class Actor(val displayName: String, val avatarObjectKey: String?, val mutedUntil: Instant?)

    private fun lockActor(connection: Connection, id: UUID): Actor = connection.prepareStatement(
        "SELECT display_name,avatar_object_key,message_muted_until FROM users WHERE id=? AND deleted_at IS NULL FOR NO KEY UPDATE"
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Active user required" }
            Actor(rows.getString(1), rows.getString(2), rows.getObject(3, OffsetDateTime::class.java)?.toInstant())
        }
    }

    private fun activeMembership(connection: Connection, chatId: UUID, actorId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL"
    ).use { statement ->
        statement.setObject(1, chatId); statement.setObject(2, actorId)
        statement.executeQuery().use { it.next() }
    }

    private fun pointId(connection: Connection, chatId: UUID): UUID? = connection.prepareStatement(
        "SELECT pt.point_id FROM chats AS c JOIN point_topics AS pt ON pt.id=c.point_topic_id WHERE c.id=?"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    private fun lockPoint(connection: Connection, id: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM points WHERE id=? FOR UPDATE"
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { it.next() }
    }

    private fun lockChat(connection: Connection, chatId: UUID): Boolean = connection.prepareStatement(
        "SELECT id FROM chats WHERE id=? FOR UPDATE"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { it.next() }
    }

    private fun nextSeq(connection: Connection, chatId: UUID): Long = connection.prepareStatement(
        "UPDATE chats SET last_message_seq=last_message_seq+1 WHERE id=? RETURNING last_message_seq"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
    }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }

    private fun floodDelay(connection: Connection, userId: UUID, now: Instant): Int? = connection.prepareStatement(
        """SELECT max(accepted_at),count(*),min(accepted_at) FROM message_rate_events
           WHERE user_id=? AND accepted_at>?"""
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, now.minusSeconds(300).atOffset(ZoneOffset.UTC))
        statement.executeQuery().use { rows ->
            check(rows.next())
            val latest = rows.getObject(1, OffsetDateTime::class.java)?.toInstant()
            val count = rows.getLong(2)
            val oldest = rows.getObject(3, OffsetDateTime::class.java)?.toInstant()
            val twoSeconds = latest?.plusSeconds(2)?.let { Duration.between(now, it).toNanos() } ?: 0
            val fiveMinutes = if (count >= 30) oldest?.plusSeconds(300)?.let { Duration.between(now, it).toNanos() } ?: 0 else 0
            val remaining = maxOf(twoSeconds, fiveMinutes)
            if (remaining > 0) ((remaining + 999_999_999L) / 1_000_000_000L).toInt().coerceAtLeast(1) else null
        }
    }
}
