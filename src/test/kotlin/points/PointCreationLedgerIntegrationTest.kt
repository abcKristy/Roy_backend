package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PointCreationLedgerIntegrationTest {
    @Test
    fun `deleting a created point keeps its ledger event and quota slot`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t2_ledger$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actorId = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actorId)
            repeat(2) {
                connection.prepareStatement(
                    "INSERT INTO point_creation_events (id,creator_user_id,created_at) VALUES (?,?,clock_timestamp() - interval '1 day')"
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, actorId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val command = CreatePointCommand(actorId, UUID.randomUUID(), PointPosition(55.751, 37.618),
                PointTopicInput("football"), "Ledger fixture", PointAddressSource.MANUAL, false)
            val created = assertIs<CreatePointResult.Created>(service.create(command, ::createdBody, ::quotaBody))
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(3, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", actorId))
                assertEquals(1, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE point_id = ?", created.value.pointId))
            }

            val left = assertIs<LeavePointChatResult.Left>(service.leave(actorId, created.value.chatId))
            assertTrue(left.pointDeleted)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE id = ?", created.value.pointId))
                assertEquals(3, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", actorId))
                assertEquals(3, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ? AND point_id IS NULL", actorId))
            }

            val next = service.create(command.copy(idempotencyKey = UUID.randomUUID()), ::createdBody, ::quotaBody)
            assertIs<CreatePointResult.QuotaExceeded>(next)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(3, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", actorId))
            }
        }
    }

    private fun createdBody(connection: Connection, value: CreatedPoint) = buildJsonObject {
        put("pointId", value.pointId.toString())
    }

    private fun quotaBody(until: java.time.Instant) = buildJsonObject { put("blockedUntil", until.toString()) }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, phone)
            assertEquals(1, statement.executeUpdate())
        }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())").use { statement ->
            statement.setObject(1, id)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun count(connection: Connection, sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            rows.getInt(1)
        }
    }
}
