package com.example.moderation

import com.example.persistence.DatabaseRuntime
import com.example.persistence.JdbcIdempotencyOperations
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ReportCommand(
    val reporterId: UUID,
    val idempotencyKey: UUID,
    val contextChatId: UUID,
    val targetUserId: UUID,
    val reasonText: String,
)

sealed interface ReportResult {
    data class Created(val id: UUID, val reportedAt: Instant, val responseSnapshot: JsonObject) : ReportResult
    data class Replay(val responseSnapshot: JsonObject) : ReportResult
    data object KeyReused : ReportResult
    data object DuplicateReport : ReportResult
    data object Forbidden : ReportResult
}

/** Reports use the same actor -> point -> chat lock order as other mutations. */
class ModerationService(
    private val database: DatabaseRuntime,
    private val idempotency: JdbcIdempotencyOperations = JdbcIdempotencyOperations(),
) {
    suspend fun report(command: ReportCommand): ReportResult {
        require(command.reporterId != command.targetUserId) { "A user cannot report themselves" }
        val reason = command.reasonText.trim()
        require('\u0000' !in reason && reason.codePointCount(0, reason.length) in 1..1000) { "Invalid report reason" }
        val hash = JdbcIdempotencyOperations.requestHash("report", emptyList(), buildJsonObject {
            put("contextChatId", command.contextChatId.toString())
            put("targetUserId", command.targetUserId.toString())
            put("reasonText", reason)
        })
        return database.withRetryingJdbcTransaction { connection ->
            val locked = lockUsersInOrder(connection, command.reporterId, command.targetUserId)
            if (locked[command.reporterId] != true) {
                return@withRetryingJdbcTransaction ReportResult.Forbidden
            }
            when (val decision = idempotency.lockActorAndCheck(
                connection, command.reporterId, "report", command.idempotencyKey, hash,
            )) {
                is JdbcIdempotencyOperations.Decision.Replay ->
                    return@withRetryingJdbcTransaction ReportResult.Replay(checkNotNull(decision.record.responseSnapshot))
                JdbcIdempotencyOperations.Decision.KeyReused ->
                    return@withRetryingJdbcTransaction ReportResult.KeyReused
                JdbcIdempotencyOperations.Decision.New -> Unit
            }
            if (locked[command.targetUserId] != true) {
                return@withRetryingJdbcTransaction ReportResult.Forbidden
            }
            val pointId = pointIdForChat(connection, command.contextChatId)
                ?: return@withRetryingJdbcTransaction ReportResult.Forbidden
            if (!lockPoint(connection, pointId) || !lockChat(connection, command.contextChatId)) {
                return@withRetryingJdbcTransaction ReportResult.Forbidden
            }
            if (!canReport(connection, command)) return@withRetryingJdbcTransaction ReportResult.Forbidden
            if (alreadyReported(connection, command)) return@withRetryingJdbcTransaction ReportResult.DuplicateReport

            val now = databaseNow(connection)
            val reportId = UUID.randomUUID()
            connection.prepareStatement(
                """INSERT INTO reports (id,reporter_user_id,target_user_id,context_chat_id,reason_text,created_at)
                   VALUES (?,?,?,?,?,?)"""
            ).use { statement ->
                statement.setObject(1, reportId)
                statement.setObject(2, command.reporterId)
                statement.setObject(3, command.targetUserId)
                statement.setObject(4, command.contextChatId)
                statement.setString(5, reason)
                statement.setObject(6, now.atOffset(ZoneOffset.UTC))
                check(statement.executeUpdate() == 1)
            }
            maybeApplyLifetimeMute(connection, command.targetUserId, now)
            val snapshot = buildJsonObject {
                put("status", 201)
                put("body", buildJsonObject {
                    put("id", reportId.toString())
                    put("reportedAt", now.toString())
                })
            }
            idempotency.recordSuccess(connection, command.reporterId, "report", command.idempotencyKey,
                hash, reportId, snapshot)
            ReportResult.Created(reportId, now, snapshot)
        }
    }

    private fun lockUsersInOrder(connection: Connection, reporter: UUID, target: UUID): Map<UUID, Boolean> {
        require(!connection.autoCommit)
        val active = mutableMapOf<UUID, Boolean>()
        for (id in listOf(reporter, target).sorted()) {
            connection.prepareStatement("SELECT deleted_at IS NULL FROM users WHERE id=? FOR NO KEY UPDATE").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows -> active[id] = rows.next() && rows.getBoolean(1) }
            }
        }
        return active
    }

    private fun pointIdForChat(connection: Connection, chatId: UUID): UUID? = connection.prepareStatement(
        """SELECT topic.point_id FROM chats AS chat
           JOIN point_topics AS topic ON topic.id=chat.point_topic_id WHERE chat.id=?"""
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

    private fun canReport(connection: Connection, command: ReportCommand): Boolean = connection.prepareStatement(
        """SELECT
             EXISTS (SELECT 1 FROM chat_memberships
                     WHERE chat_id=? AND user_id=? AND left_at IS NULL),
             EXISTS (SELECT 1 FROM chat_memberships
                     WHERE chat_id=? AND user_id=? AND left_at IS NULL)
               OR EXISTS (SELECT 1 FROM messages
                          WHERE chat_id=? AND author_user_id=? AND type='TEXT')"""
    ).use { statement ->
        statement.setObject(1, command.contextChatId)
        statement.setObject(2, command.reporterId)
        statement.setObject(3, command.contextChatId)
        statement.setObject(4, command.targetUserId)
        statement.setObject(5, command.contextChatId)
        statement.setObject(6, command.targetUserId)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) && rows.getBoolean(2) }
    }

    private fun alreadyReported(connection: Connection, command: ReportCommand): Boolean = connection.prepareStatement(
        "SELECT 1 FROM reports WHERE reporter_user_id=? AND target_user_id=?"
    ).use { statement ->
        statement.setObject(1, command.reporterId)
        statement.setObject(2, command.targetUserId)
        statement.executeQuery().use { it.next() }
    }

    private fun maybeApplyLifetimeMute(connection: Connection, target: UUID, now: Instant) {
        val count = connection.prepareStatement("SELECT count(*) FROM reports WHERE target_user_id=?").use { statement ->
            statement.setObject(1, target)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
        if (count < 16) return
        connection.prepareStatement(
            """UPDATE users SET message_muted_until=?,report_mute_triggered_at=?,updated_at=?
               WHERE id=? AND deleted_at IS NULL AND report_mute_triggered_at IS NULL"""
        ).use { statement ->
            statement.setObject(1, now.plus(7, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
            statement.setObject(2, now.atOffset(ZoneOffset.UTC))
            statement.setObject(3, now.atOffset(ZoneOffset.UTC))
            statement.setObject(4, target)
            statement.executeUpdate()
        }
    }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next())
            rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }
}
