package com.example.jobs

import com.example.persistence.DatabaseRuntime
import com.example.persistence.JdbcSystemMessageWriter
import com.example.persistence.StoredSystemMessage
import com.example.persistence.SystemMessageDraft
import com.example.persistence.SystemMessageKind
import com.example.persistence.withRetryingJdbcTransaction
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class ExpiredProposal(val proposalId: UUID, val chatId: UUID, val revision: Long,
                           val goingCount: Int, val updatedAt: Instant,
                           val message: StoredSystemMessage, val draft: SystemMessageDraft)

/** Time closes only an unbooked OPEN proposal; an ACTIVE booking stays as meeting history. */
class ProposalExpiryScheduler(
    private val database: DatabaseRuntime,
    private val systemMessages: JdbcSystemMessageWriter = JdbcSystemMessageWriter(),
) {
    suspend fun tick(limit: Int = 100): List<ExpiredProposal> {
        require(limit in 1..500)
        val ids = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                """SELECT p.id FROM meeting_proposals AS p
                   WHERE p.lifecycle='OPEN' AND p.starts_at<=clock_timestamp()
                     AND NOT EXISTS (SELECT 1 FROM bookings AS b
                         WHERE b.proposal_id=p.id AND b.status='ACTIVE')
                   ORDER BY p.starts_at,p.id LIMIT ?"""
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java))
                } }
            }
        }
        return ids.mapNotNull { expire(it) }
    }

    private suspend fun expire(proposalId: UUID): ExpiredProposal? = database.withRetryingJdbcTransaction { connection ->
        val target = connection.prepareStatement(
            """SELECT pt.point_id,p.chat_id FROM meeting_proposals AS p
               JOIN chats AS c ON c.id=p.chat_id JOIN point_topics AS pt ON pt.id=c.point_topic_id
               WHERE p.id=?"""
        ).use { statement ->
            statement.setObject(1, proposalId)
            statement.executeQuery().use { rows -> if (rows.next()) Pair(
                rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java)) else null }
        } ?: return@withRetryingJdbcTransaction null
        if (!lockPoint(connection, target.first)) return@withRetryingJdbcTransaction null
        val current = connection.prepareStatement(
            """SELECT p.starts_at,p.lifecycle FROM meeting_proposals AS p WHERE p.id=? FOR UPDATE"""
        ).use { statement ->
            statement.setObject(1, proposalId)
            statement.executeQuery().use { rows -> if (rows.next()) Pair(
                rows.getObject(1, OffsetDateTime::class.java), rows.getString(2)) else null }
        } ?: return@withRetryingJdbcTransaction null
        if (current.second != "OPEN" || current.first.toInstant().isAfter(databaseNow(connection))) {
            return@withRetryingJdbcTransaction null
        }
        if (connection.prepareStatement(
            "SELECT 1 FROM bookings WHERE proposal_id=? AND status='ACTIVE'"
        ).use { statement ->
            statement.setObject(1, proposalId)
            statement.executeQuery().use { it.next() }
        }) return@withRetryingJdbcTransaction null
        val update = connection.prepareStatement(
            """UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='TIME',
               revision=revision+1,updated_at=clock_timestamp() WHERE id=? RETURNING revision,updated_at"""
        ).use { statement ->
            statement.setObject(1, proposalId)
            statement.executeQuery().use { rows -> rows.next();
                rows.getLong(1) to rows.getObject(2, OffsetDateTime::class.java).toInstant() }
        }
        val goingCount = connection.prepareStatement("SELECT count(*) FROM votes WHERE proposal_id=?").use { statement ->
            statement.setObject(1, proposalId)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        val draft = SystemMessageDraft(SystemMessageKind.MEETING_CLOSED, null, target.first, target.second,
            proposalId = proposalId)
        val message = systemMessages.append(connection, UUID.randomUUID(), draft)
        ExpiredProposal(proposalId, target.second, update.first, goingCount, update.second, message, draft)
    }

    private fun lockPoint(connection: Connection, id: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM points WHERE id=? FOR UPDATE"
    ).use { statement -> statement.setObject(1, id); statement.executeQuery().use { it.next() } }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            rows.next(); rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }
}
