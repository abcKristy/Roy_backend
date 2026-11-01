package com.example.persistence

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class JdbcCoreOperationsIntegrationTest {
    @Test
    fun `idempotency outcome and system message share caller transaction`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url)) {
            "TEST_DATABASE_URL must point to a disposable roy_m1t2_ database"
        }
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val runtime = HikariDatabaseBootstrap(
            ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.PRODUCTION)
        ).start()
        val actorId = UUID.randomUUID()
        val pointId = UUID.randomUUID()
        val topicId = UUID.randomUUID()
        val chatId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val key = UUID.randomUUID()
        val operations = JdbcIdempotencyOperations()
        val writer = JdbcSystemMessageWriter()
        val business = buildJsonObject { put("text", "hello"); put("confirmNearby", false); put("expectedRevision", 1) }
        val sameBusiness = buildJsonObject { put("expectedRevision", 2); put("confirmNearby", true); put("text", "hello") }
        val hash = JdbcIdempotencyOperations.requestHash("send_message", listOf(chatId), business)
        assertEquals(hash, JdbcIdempotencyOperations.requestHash("send_message", listOf(chatId), sameBusiness))
        assertNotEquals(hash, JdbcIdempotencyOperations.requestHash("send_message", listOf(pointId), sameBusiness))

        runtime.use {
            it.withJdbcTransaction { connection -> seed(connection, actorId, pointId, topicId, chatId) }
            val stored = it.withJdbcTransaction { connection ->
                assertIs<JdbcIdempotencyOperations.Decision.New>(
                    operations.lockActorAndCheck(connection, actorId, "send_message", key, hash)
                )
                val message = writer.append(
                    connection, messageId,
                    SystemMessageDraft(SystemMessageKind.MEMBER_JOINED, actorId, pointId, chatId)
                )
                val response = buildJsonObject { put("messageId", message.id.toString()) }
                operations.recordSuccess(connection, actorId, "send_message", key, hash,
                    message.id, response)
                message
            }
            assertEquals(1L, stored.messageSeq)

            val rolledBackId = UUID.randomUUID()
            try {
                it.withJdbcTransaction { connection ->
                    writer.append(connection, rolledBackId,
                        SystemMessageDraft(SystemMessageKind.MEMBER_LEFT, actorId, pointId, chatId))
                    error("rollback probe")
                }
                error("Transaction unexpectedly committed")
            } catch (failure: IllegalStateException) {
                assertEquals("rollback probe", failure.message)
            }

            it.withJdbcTransaction { connection ->
                val replay = assertIs<JdbcIdempotencyOperations.Decision.Replay>(
                    operations.lockActorAndCheck(connection, actorId, "send_message", key, hash)
                )
                assertEquals(messageId, replay.record.resultRef)
                assertEquals("SUCCESS", replay.record.outcome.name)
                assertIs<JdbcIdempotencyOperations.Decision.KeyReused>(
                    operations.lockActorAndCheck(connection, actorId, "send_message", key,
                        JdbcIdempotencyOperations.requestHash("send_message", listOf(chatId),
                            buildJsonObject { put("text", "changed") }))
                )
                connection.prepareStatement(
                    "SELECT last_message_seq FROM chats WHERE id = ?"
                ).use { statement ->
                    statement.setObject(1, chatId)
                    statement.executeQuery().use { rows -> rows.next(); assertEquals(1L, rows.getLong(1)) }
                }
                connection.prepareStatement(
                    "SELECT system_payload->>'kind', system_payload->>'chatId' FROM messages WHERE id = ?"
                ).use { statement ->
                    statement.setObject(1, messageId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals("member_joined", rows.getString(1))
                        assertEquals(chatId.toString(), rows.getString(2))
                    }
                }
                connection.prepareStatement("SELECT count(*) FROM messages WHERE id = ?").use { statement ->
                    statement.setObject(1, rolledBackId)
                    statement.executeQuery().use { rows -> rows.next(); assertEquals(0L, rows.getLong(1)) }
                }
            }

            val quotaKey = UUID.randomUUID()
            val quotaHash = JdbcIdempotencyOperations.requestHash("create_point", emptyList(),
                buildJsonObject { put("address", "Moscow") })
            val blockedUntil = it.withJdbcTransaction { connection ->
                assertIs<JdbcIdempotencyOperations.Decision.New>(
                    operations.lockActorAndCheck(connection, actorId, "create_point", quotaKey, quotaHash)
                )
                operations.recordQuotaExceeded(connection, actorId, "create_point", quotaKey,
                    quotaHash) { actualBlockedUntil ->
                    buildJsonObject { put("blockedUntil", actualBlockedUntil.toString()) }
                }
            }
            val quotaResponse: JsonObject = buildJsonObject { put("blockedUntil", blockedUntil.toString()) }
            it.withJdbcTransaction { connection ->
                val replay = assertIs<JdbcIdempotencyOperations.Decision.Replay>(
                    operations.lockActorAndCheck(connection, actorId, "create_point", quotaKey, quotaHash)
                )
                assertEquals(JdbcIdempotencyOperations.Outcome.QUOTA_EXCEEDED, replay.record.outcome)
                assertEquals(quotaResponse, replay.record.responseSnapshot)
                connection.prepareStatement("SELECT create_point_blocked_until FROM users WHERE id = ?").use { statement ->
                    statement.setObject(1, actorId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(blockedUntil, rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant())
                    }
                }
                connection.prepareStatement(
                    """SELECT u.updated_at, i.created_at FROM users u
                       JOIN operation_idempotency i ON i.actor_user_id = u.id
                       WHERE u.id = ? AND i.operation_type = 'create_point' AND i.idempotency_key = ?"""
                ).use { statement ->
                    statement.setObject(1, actorId)
                    statement.setString(2, quotaKey.toString())
                    statement.executeQuery().use { rows ->
                        rows.next()
                        val attemptedAt = rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant()
                        val recordedAt = rows.getObject(2, java.time.OffsetDateTime::class.java).toInstant()
                        assertEquals(attemptedAt, recordedAt)
                        assertEquals(attemptedAt.plus(30, ChronoUnit.DAYS), blockedUntil)
                    }
                }
            }
        }
    }

    private fun seed(connection: Connection, actorId: UUID, pointId: UUID, topicId: UUID, chatId: UUID) {
        val phone = "+7" + actorId.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Tester',20,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { it.setObject(1, actorId); it.setString(2, phone); it.executeUpdate() }
        connection.prepareStatement(
            """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
               VALUES (?,ST_SetSRID(ST_MakePoint(37,55),4326),'Moscow','MANUAL',?,clock_timestamp(),clock_timestamp())"""
        ).use { it.setObject(1, pointId); it.setObject(2, actorId); it.executeUpdate() }
        connection.prepareStatement(
            "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
        ).use { it.setObject(1, topicId); it.setObject(2, pointId); it.executeUpdate() }
        connection.prepareStatement(
            "INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())"
        ).use { it.setObject(1, chatId); it.setObject(2, topicId); it.executeUpdate() }
    }
}
