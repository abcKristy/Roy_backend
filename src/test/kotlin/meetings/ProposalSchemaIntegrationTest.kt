package com.example.meetings

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the applied schema itself, with every synthetic row rolled back. */
class ProposalSchemaIntegrationTest {
    @Test
    fun `proposal schema preserves links UTC interval and lifecycle invariants`() {
        val url = testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m4_schema$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "m4-schema-test-secret-at-least-32-bytes", appEnvironment = AppEnvironment.LOCAL))
            .start().use {
                DriverManager.getConnection(url, user, password).use { c ->
                    c.autoCommit = false
                    try {
                        val proposer = UUID.randomUUID()
                        val point = UUID.randomUUID()
                        val topic = UUID.randomUUID()
                        val chat = UUID.randomUUID()
                        val proposal = UUID.randomUUID()
                        val starts = OffsetDateTime.parse("2030-04-12T18:30:00+03:00")
                        val ends = starts.plusMinutes(90)
                        insertUser(c, proposer)
                        execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                            VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic schema fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""",
                            point, proposer)
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                        insertProposal(c, proposal, chat, proposer, starts, ends)
                        c.prepareStatement("SELECT chat_id,proposer_user_id,starts_at,ends_at,lifecycle,closed_reason,revision FROM meeting_proposals WHERE id=?").use { s ->
                            s.setObject(1, proposal)
                            s.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                assertEquals(chat, rows.getObject("chat_id", UUID::class.java))
                                assertEquals(proposer, rows.getObject("proposer_user_id", UUID::class.java))
                                assertEquals(starts.toInstant(), rows.getObject("starts_at", OffsetDateTime::class.java).toInstant())
                                assertEquals(ends.toInstant(), rows.getObject("ends_at", OffsetDateTime::class.java).toInstant())
                                assertEquals("OPEN", rows.getString("lifecycle"))
                                assertNull(rows.getString("closed_reason"))
                                assertEquals(0L, rows.getLong("revision"))
                                assertFalse(rows.next())
                            }
                        }
                        expectSqlState(c, "23503") { insertProposal(c, UUID.randomUUID(), UUID.randomUUID(), proposer, starts, ends) }
                        expectSqlState(c, "23503") { insertProposal(c, UUID.randomUUID(), chat, UUID.randomUUID(), starts, ends) }
                        expectSqlState(c, "23514") { insertProposal(c, UUID.randomUUID(), chat, proposer, starts, starts) }
                        expectSqlState(c, "23514") { insertProposal(c, UUID.randomUUID(), chat, proposer, starts, starts.minusSeconds(1)) }
                        expectSqlState(c, "23514") { execute(c, "UPDATE meeting_proposals SET revision=-1 WHERE id=?", proposal) }
                        expectSqlState(c, "23514") { execute(c, "UPDATE meeting_proposals SET lifecycle='INVALID' WHERE id=?", proposal) }
                        expectSqlState(c, "23514") { execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED' WHERE id=?", proposal) }
                        expectSqlState(c, "23514") { execute(c, "UPDATE meeting_proposals SET closed_reason='AUTHOR' WHERE id=?", proposal) }
                        expectSqlState(c, "23514") { execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='INVALID' WHERE id=?", proposal) }
                        expectSqlState(c, "P0001") { execute(c, "UPDATE meeting_proposals SET starts_at=starts_at+interval '1 minute' WHERE id=?", proposal) }
                        expectSqlState(c, "P0001") { execute(c, "UPDATE meeting_proposals SET ends_at=ends_at+interval '1 minute' WHERE id=?", proposal) }
                        // A no-op interval update and a legitimate lifecycle/revision update remain legal.
                        execute(c, "UPDATE meeting_proposals SET starts_at=starts_at,ends_at=ends_at,revision=revision+1,lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", proposal)
                        c.prepareStatement("SELECT starts_at,ends_at,lifecycle,closed_reason,revision FROM meeting_proposals WHERE id=?").use { s ->
                            s.setObject(1, proposal)
                            s.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                assertEquals(starts.toInstant(), rows.getObject(1, OffsetDateTime::class.java).toInstant())
                                assertEquals(ends.toInstant(), rows.getObject(2, OffsetDateTime::class.java).toInstant())
                                assertEquals("CLOSED", rows.getString(3))
                                assertEquals("AUTHOR", rows.getString(4))
                                assertEquals(1L, rows.getLong(5))
                            }
                        }
                        // Proposer deletion retains historical proposal; deleting its chat cascades it.
                        execute(c, "DELETE FROM users WHERE id=?", proposer)
                        c.prepareStatement("SELECT proposer_user_id FROM meeting_proposals WHERE id=?").use { s ->
                            s.setObject(1, proposal)
                            s.executeQuery().use { rows -> assertTrue(rows.next()); assertNull(rows.getObject(1)) }
                        }
                        execute(c, "DELETE FROM chats WHERE id=?", chat)
                        c.prepareStatement("SELECT 1 FROM meeting_proposals WHERE id=?").use { s ->
                            s.setObject(1, proposal)
                            s.executeQuery().use { assertFalse(it.next()) }
                        }
                    } finally {
                        c.rollback()
                    }
                }
            }
    }

    private fun expectSqlState(c: Connection, state: String, block: () -> Unit) {
        val savepoint = c.setSavepoint()
        try {
            assertEquals(state, assertFailsWith<SQLException> { block() }.sqlState)
        } finally {
            c.rollback(savepoint)
            c.releaseSavepoint(savepoint)
        }
    }

    private fun insertUser(c: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
            VALUES (?,?,'synthetic-test-hash','Schema fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
    }

    private fun insertProposal(c: Connection, id: UUID, chat: UUID, proposer: UUID, starts: OffsetDateTime, ends: OffsetDateTime) {
        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
            VALUES (?,?,?,?,?,clock_timestamp(),clock_timestamp())""", id, chat, proposer, starts, ends)
    }

    private fun execute(c: Connection, sql: String, vararg values: Any) {
        c.prepareStatement(sql).use { s ->
            values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
            s.executeUpdate()
        }
    }
}
