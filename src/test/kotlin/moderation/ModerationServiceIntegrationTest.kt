package com.example.moderation

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ModerationServiceIntegrationTest {
    @Test
    fun `unique lifetime reports mute exactly once and retain replay`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step5moderation$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val reporters = List(17) { UUID.randomUUID() }
        val target = UUID.randomUUID()
        val authorTarget = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            try {
                (reporters + target + authorTarget + outsider).forEach { insertUser(connection, it) }
                execute(connection,
                    """INSERT INTO points (id,location,address,address_source,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37,55),4326),'Test','MANUAL',clock_timestamp(),clock_timestamp())""",
                    point)
                execute(connection,
                    "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())",
                    topic, point)
                execute(connection,
                    "INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())",
                    chat, topic)
                (reporters + target).forEach { id -> execute(connection,
                    "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())",
                    chat, id) }
                execute(connection,
                    """INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at)
                       VALUES (?,?,1,?,'TEXT','Visible message',clock_timestamp())""",
                    UUID.randomUUID(), chat, authorTarget)
                execute(connection, "UPDATE chats SET last_message_seq=1 WHERE id=?", chat)
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret",
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = ModerationService(runtime)
            val keys = List(17) { UUID.randomUUID() }
            for (index in 0 until 16) {
                val reason = if (index == 0) "😀".repeat(1000) else "Reason $index"
                assertIs<ReportResult.Created>(service.report(ReportCommand(
                    reporters[index], keys[index], chat, target, reason)))
            }
            val muteAt16 = DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    "SELECT message_muted_until,report_mute_triggered_at FROM users WHERE id=?"
                ).use { statement ->
                    statement.setObject(1, target)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        val until = rows.getObject(1, OffsetDateTime::class.java)?.toInstant()
                        val triggered = rows.getObject(2, OffsetDateTime::class.java)?.toInstant()
                        assertNotNull(until)
                        assertNotNull(triggered)
                        until to triggered
                    }
                }
            }
            val repeated = assertIs<ReportResult.Replay>(service.report(ReportCommand(
                reporters[15], keys[15], chat, target, "Reason 15")))
            assertEquals(201, repeated.responseSnapshot["status"]?.toString()?.toInt())
            assertIs<ReportResult.DuplicateReport>(service.report(ReportCommand(
                reporters[15], UUID.randomUUID(), chat, target, "Other reason")))
            assertIs<ReportResult.Created>(service.report(ReportCommand(
                reporters[16], keys[16], chat, target, "Reason 16")))
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    "SELECT message_muted_until,report_mute_triggered_at FROM users WHERE id=?"
                ).use { statement ->
                    statement.setObject(1, target)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(muteAt16.first, rows.getObject(1, OffsetDateTime::class.java).toInstant())
                        assertEquals(muteAt16.second, rows.getObject(2, OffsetDateTime::class.java).toInstant())
                    }
                }
            }
            assertIs<ReportResult.Forbidden>(service.report(ReportCommand(
                outsider, UUID.randomUUID(), chat, target, "No membership")))
            assertIs<ReportResult.Created>(service.report(ReportCommand(
                reporters[0], UUID.randomUUID(), chat, authorTarget, "Authored a visible message")))
            assertFailsWith<IllegalArgumentException> { service.report(ReportCommand(
                reporters[0], UUID.randomUUID(), chat, reporters[0], "Self")) }
            assertFailsWith<IllegalArgumentException> { service.report(ReportCommand(
                reporters[0], UUID.randomUUID(), chat, authorTarget, "😀".repeat(1001))) }
        }
        Unit
    }

    private fun insertUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, phone)
            statement.executeUpdate()
        }
    }

    private fun execute(connection: Connection, sql: String, vararg ids: UUID) {
        connection.prepareStatement(sql).use { statement ->
            ids.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
            statement.executeUpdate()
        }
    }
}
