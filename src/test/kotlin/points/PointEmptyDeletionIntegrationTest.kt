package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PointEmptyDeletionIntegrationTest {
    @Test
    fun `last leave deletes every topic and chat but retains creation ledger`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t5_delete$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actors = List(2) { UUID.randomUUID() }
        val point = UUID.randomUUID()
        val topics = List(2) { UUID.randomUUID() }
        val chats = List(2) { UUID.randomUUID() }
        val ledger = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            actors.forEach { seedUser(connection, it) }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Deletion fixture','MANUAL',?,
                           clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, point)
                statement.setObject(2, actors[0])
                assertEquals(1, statement.executeUpdate())
            }
            listOf("football" to null, "other" to "Cleanup").forEachIndexed { index, topic ->
                connection.prepareStatement(
                    """INSERT INTO point_topics (id,point_id,preset_key,custom_label,created_at)
                       VALUES (?,?,?,?,clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, topics[index])
                    statement.setObject(2, point)
                    statement.setString(3, topic.first)
                    statement.setString(4, topic.second)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    "INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, chats[index])
                    statement.setObject(2, topics[index])
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, chats[index])
                    statement.setObject(2, actors[index])
                    assertEquals(1, statement.executeUpdate())
                }
            }
            connection.prepareStatement(
                "INSERT INTO point_creation_events (id,creator_user_id,point_id,created_at) VALUES (?,?,?,clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, ledger)
                statement.setObject(2, actors[0])
                statement.setObject(3, point)
                assertEquals(1, statement.executeUpdate())
            }
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "delete-test-secret",
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val first = assertIs<LeavePointChatResult.Left>(service.leave(actors[0], chats[0]))
            assertEquals(point, first.pointId)
            assertEquals(false, first.pointDeleted)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id=?", point))
                assertEquals(2, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", point))
                assertEquals(1, activeMembers(connection, point))
                assertEquals(1, count(connection, "SELECT count(*) FROM point_creation_events WHERE id=? AND point_id IS NOT NULL", ledger))
            }

            val last = assertIs<LeavePointChatResult.Left>(service.leave(actors[1], chats[1]))
            assertEquals(point, last.pointId)
            assertEquals(true, last.pointDeleted)
            assertEquals(actors.toSet(), last.deletedRecipients.toSet())
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE id=?", point))
                assertEquals(0, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", point))
                chats.forEach { chat ->
                    assertEquals(0, count(connection, "SELECT count(*) FROM chats WHERE id=?", chat))
                    assertEquals(0, count(connection, "SELECT count(*) FROM chat_memberships WHERE chat_id=?", chat))
                    assertEquals(0, count(connection, "SELECT count(*) FROM messages WHERE chat_id=?", chat))
                }
                assertEquals(1, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE id=? AND point_id IS NULL", ledger))
                assertEquals(1, count(connection,
                    "SELECT count(*) FROM points WHERE id=?",
                    UUID.fromString("20000000-0000-4000-8000-000000000001")))
            }
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Delete tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, phone)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun activeMembers(connection: Connection, point: UUID): Int = connection.prepareStatement(
        """SELECT count(*) FROM chat_memberships AS m JOIN chats AS c ON c.id=m.chat_id
           JOIN point_topics AS t ON t.id=c.point_topic_id
           WHERE t.point_id=? AND m.left_at IS NULL"""
    ).use { statement ->
        statement.setObject(1, point)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }

    private fun count(connection: Connection, sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
