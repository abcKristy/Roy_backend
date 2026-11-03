package com.example.points

import com.example.api.PointWriteSnapshot
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddTopicAutojoinIntegrationTest {
    @Test
    fun `add topic commits chat membership and replay together or rolls all back`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t3_autojoin$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actorId = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actorId)
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val latitude = ThreadLocalRandom.current().nextDouble(40.0, 60.0)
            val longitude = ThreadLocalRandom.current().nextDouble(20.0, 40.0)
            val point = assertIs<CreatePointResult.Created>(service.create(
                CreatePointCommand(actorId, UUID.randomUUID(), PointPosition(latitude, longitude),
                    PointTopicInput("football"), "Autojoin fixture", PointAddressSource.MANUAL, false),
                PointWriteSnapshot::create, ::quotaBody,
            ))
            val command = AddTopicCommand(actorId, UUID.randomUUID(), point.value.pointId, PointTopicInput("tennis"))
            val added = assertIs<AddTopicResult.Created>(service.addTopic(command, PointWriteSnapshot::addTopic))
            val body = added.responseSnapshot.getValue("body").jsonObject
            assertEquals(added.value.topicId.toString(), body.getValue("topic").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(added.value.chatId.toString(), body.getValue("chat").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(actorId.toString(), body.getValue("membership").jsonObject.getValue("userId").jsonPrimitive.content)

            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(2, count(connection, "SELECT count(*) FROM point_topics WHERE point_id = ?", point.value.pointId))
                assertEquals(1, count(connection, "SELECT count(*) FROM chats WHERE id = ? AND participant_count = 1", added.value.chatId))
                assertEquals(1, count(connection, "SELECT count(*) FROM chat_memberships WHERE chat_id = ? AND user_id IS NOT NULL AND left_at IS NULL", added.value.chatId))
                assertEquals(1, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND operation_type = 'add_topic'", actorId))
            }
            assertEquals(added.responseSnapshot,
                assertIs<AddTopicResult.Replay>(service.addTopic(command, PointWriteSnapshot::addTopic)).responseSnapshot)

            val failing = command.copy(idempotencyKey = UUID.randomUUID(), topic = PointTopicInput("other", "Rollback topic"))
            assertFailsWith<IllegalStateException> {
                service.addTopic(failing, { _, _ -> error("snapshot failed") })
            }
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(2, count(connection, "SELECT count(*) FROM point_topics WHERE point_id = ?", point.value.pointId))
                assertEquals(1, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND operation_type = 'add_topic'", actorId))
                assertTrue(connection.prepareStatement(
                    "SELECT NOT EXISTS (SELECT 1 FROM point_topics WHERE point_id = ? AND custom_label = 'Rollback topic')"
                ).use { statement ->
                    statement.setObject(1, point.value.pointId)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                })
            }
        }
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
