package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PointJoinLastLeaveRaceIntegrationTest {
    @Test
    fun `concurrent join and last leave serialize to a valid point state`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t5_race$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val first = UUID.randomUUID()
        val joining = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val ledger = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            listOf(first, joining).forEach { seedUser(connection, it) }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),'Race fixture','MANUAL',?,
                           clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, point)
                statement.setDouble(2, ThreadLocalRandom.current().nextDouble(-150.0, 150.0))
                statement.setDouble(3, ThreadLocalRandom.current().nextDouble(-60.0, 60.0))
                statement.setObject(4, first)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, topic)
                statement.setObject(2, point)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())").use { statement ->
                statement.setObject(1, chat)
                statement.setObject(2, topic)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, chat)
                statement.setObject(2, first)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO point_creation_events (id,creator_user_id,point_id,created_at) VALUES (?,?,?,clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, ledger)
                statement.setObject(2, first)
                statement.setObject(3, point)
                assertEquals(1, statement.executeUpdate())
            }
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "point-race-test-secret",
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val start = CompletableDeferred<Unit>()
            val joinRequest = async(Dispatchers.Default) {
                start.await()
                service.join(joining, chat)
            }
            val lastLeaveRequest = async(Dispatchers.Default) {
                start.await()
                service.leave(first, chat)
            }
            start.complete(Unit)
            val joinResult = joinRequest.await()
            val leaveResult = assertIs<LeavePointChatResult.Left>(lastLeaveRequest.await())
            assertEquals(point, leaveResult.pointId)
            DriverManager.getConnection(url, user, password).use { connection ->
                val pointRows = count(connection, "SELECT count(*) FROM points WHERE id=?", point)
                val topicRows = count(connection, "SELECT count(*) FROM point_topics WHERE id=?", topic)
                val chatRows = count(connection, "SELECT count(*) FROM chats WHERE id=?", chat)
                val activeMembers = count(connection,
                    "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND left_at IS NULL", chat)
                when (joinResult) {
                    is JoinPointChatResult.Joined -> {
                        assertEquals(false, leaveResult.pointDeleted)
                        assertEquals(1, pointRows)
                        assertEquals(1, topicRows)
                        assertEquals(1, chatRows)
                        assertEquals(1, activeMembers)
                        assertEquals(1, count(connection,
                            "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id IS NOT NULL AND left_at IS NOT NULL", chat))
                        assertEquals(1, count(connection,
                            "SELECT count(*) FROM point_creation_events WHERE id=? AND point_id IS NOT NULL", ledger))
                    }
                    JoinPointChatResult.ChatGone -> {
                        assertEquals(true, leaveResult.pointDeleted)
                        assertEquals(0, pointRows)
                        assertEquals(0, topicRows)
                        assertEquals(0, chatRows)
                        assertEquals(0, activeMembers)
                        assertEquals(0, count(connection, "SELECT count(*) FROM chat_memberships WHERE chat_id=?", chat))
                        assertEquals(0, count(connection, "SELECT count(*) FROM messages WHERE chat_id=?", chat))
                        assertEquals(1, count(connection,
                            "SELECT count(*) FROM point_creation_events WHERE id=? AND point_id IS NULL", ledger))
                    }
                    is JoinPointChatResult.AlreadyMember -> error("Joining actor was not a member before the race")
                }
                assertEquals(0, count(connection,
                    """SELECT count(*) FROM chats AS c LEFT JOIN point_topics AS t ON t.id=c.point_topic_id
                       WHERE c.id=? AND t.id IS NULL""", chat))
                assertTrue(connection.prepareStatement(
                    "SELECT point_id IS NULL FROM point_creation_events WHERE id=?"
                ).use { statement ->
                    statement.setObject(1, ledger)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) == leaveResult.pointDeleted }
                })
            }
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Race user',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); assertEquals(1, statement.executeUpdate()) }
    }

    private fun count(connection: Connection, sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
