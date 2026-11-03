package com.example.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class JdbcNotificationWriterIntegrationTest {
    @Test
    fun `chat and nearby recipients are selected in the same transaction`() {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step3notify$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                val actor = UUID.randomUUID()
                val member = UUID.randomUUID()
                val near = UUID.randomUUID()
                val far = UUID.randomUUID()
                val noOptIn = UUID.randomUUID()
                val former = UUID.randomUUID()
                listOf(actor, member, near, far, noOptIn, former).forEach { insertRecipient(connection, it) }
                val point = UUID.randomUUID()
                val topic = UUID.randomUUID()
                val chat = UUID.randomUUID()
                val proposal = UUID.randomUUID()
                execute(connection,
                    """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37.6184,55.7512),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())""",
                    point, actor)
                execute(connection,
                    "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())",
                    topic, point)
                execute(connection,
                    "INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())",
                    chat, topic)
                listOf(actor, member).forEach { id -> execute(connection,
                    "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())",
                    chat, id) }
                execute(connection,
                    """INSERT INTO chat_memberships (chat_id,user_id,joined_at,left_at)
                       VALUES (?,?,clock_timestamp() - interval '1 day',clock_timestamp())""",
                    chat, former)
                execute(connection,
                    """INSERT INTO meeting_proposals
                       (id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                       VALUES (?,?,?,clock_timestamp() + interval '1 day',
                               clock_timestamp() + interval '1 day 1 hour',clock_timestamp(),clock_timestamp())""",
                    proposal, chat, actor)
                listOf(actor, member, near, noOptIn, former).forEach { id ->
                    execute(connection,
                        """INSERT INTO notification_locations (user_id,position,updated_at)
                           VALUES (?,ST_SetSRID(ST_MakePoint(37.6184,55.7512),4326)::geography,clock_timestamp())""",
                        id)
                }
                execute(connection,
                    """INSERT INTO notification_locations (user_id,position,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37.8,55.7512),4326)::geography,clock_timestamp())""",
                    far)
                listOf(actor, member, near, far, former).forEach { id -> execute(connection,
                    "UPDATE notification_settings SET extra_in_app_enabled = true WHERE user_id = ?", id) }

                val recipients = JdbcNotificationRecipients()
                assertEquals(listOf(member), recipients.activeChatMembers(connection, chat, actor))
                assertEquals(setOf(near, former), recipients.nearbyForProposal(connection, proposal).toSet())
            } finally {
                connection.rollback()
            }
        }
    }

    @Test
    fun `event dedupe channel snapshot and device jobs share one transaction`() {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step3notify$"))) {
            "TEST_DATABASE_URL must point to the disposable notification test database"
        }
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                val recipient = UUID.randomUUID()
                insertRecipient(connection, recipient)
                insertDevice(connection, recipient, revoked = false)
                insertDevice(connection, recipient, revoked = false)
                insertDevice(connection, recipient, revoked = true)
                val missing = UUID.randomUUID()
                assertFailsWith<IllegalArgumentException> {
                    RecipientBarrier.acquire(connection, listOf(
                        RecipientLockClaim(recipient, RecipientLockMode.SHARED),
                        RecipientLockClaim(recipient, RecipientLockMode.EXCLUSIVE),
                    ))
                }
                val barrier = RecipientBarrier.acquire(
                    connection, listOf(
                        RecipientLockClaim(recipient, RecipientLockMode.SHARED),
                        RecipientLockClaim(missing, RecipientLockMode.SHARED),
                    )
                )
                val writer = JdbcNotificationWriter()
                val draft = NotificationEventDraft(
                    recipient, NotificationEventType.CHAT_MESSAGE, "chat:test:${UUID.randomUUID()}",
                    JsonObject(mapOf("kind" to JsonPrimitive("chat_message"))),
                    chatId = UUID.randomUUID(),
                )
                assertFailsWith<IllegalArgumentException> {
                    writer.write(connection, barrier, draft.copy(chatId = null))
                }
                assertFailsWith<IllegalArgumentException> {
                    writer.write(connection, barrier, draft.copy(
                        eventType = NotificationEventType.PROPOSAL_VOTE,
                        proposalId = UUID.randomUUID(),
                        chatId = null,
                    ))
                }
                assertFailsWith<IllegalArgumentException> {
                    writer.write(connection, barrier, draft.copy(
                        eventType = NotificationEventType.NEARBY_MEETING,
                        proposalId = UUID.randomUUID(),
                        pointId = null,
                    ))
                }
                val created = assertIs<NotificationWriteResult.Created>(writer.write(connection, barrier, draft))
                assertTrue(created.visibleInApp)
                assertEquals(2, created.pushJobsCreated)
                assertEquals(created.eventId, assertIs<NotificationWriteResult.Duplicate>(
                    writer.write(connection, barrier, draft)
                ).eventId)

                connection.prepareStatement(
                    "UPDATE notification_settings SET personal_push_enabled = false WHERE user_id = ?"
                ).use { statement -> statement.setObject(1, recipient); statement.executeUpdate() }
                val inAppOnly = assertIs<NotificationWriteResult.Created>(writer.write(
                    connection, barrier, draft.copy(dedupeKey = "chat:test:${UUID.randomUUID()}")
                ))
                assertTrue(inAppOnly.visibleInApp)
                assertEquals(0, inAppOnly.pushJobsCreated)

                val nearby = draft.copy(
                    eventType = NotificationEventType.NEARBY_MEETING,
                    dedupeKey = "nearby:${UUID.randomUUID()}",
                    pointId = UUID.randomUUID(),
                    proposalId = UUID.randomUUID(),
                )
                assertIs<NotificationWriteResult.ExtraDisabled>(writer.write(connection, barrier, nearby))
                connection.prepareStatement(
                    "UPDATE notification_settings SET extra_in_app_enabled = true WHERE user_id = ?"
                ).use { statement -> statement.setObject(1, recipient); statement.executeUpdate() }
                val extraInApp = assertIs<NotificationWriteResult.Created>(writer.write(connection, barrier, nearby))
                assertTrue(extraInApp.visibleInApp)
                assertEquals(0, extraInApp.pushJobsCreated)

                assertIs<NotificationWriteResult.RecipientUnavailable>(writer.write(
                    connection, barrier, draft.copy(recipientUserId = missing)
                ))
                assertEquals(3, count(connection, "notification_events"))
                assertEquals(2, count(connection, "push_delivery_jobs"))
            } finally {
                connection.rollback()
            }
        }
    }

    private fun insertRecipient(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,?,'Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, phone)
            statement.setString(3, "test-hash")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())"
        ).use { statement -> statement.setObject(1, id); statement.executeUpdate() }
    }

    private fun insertDevice(connection: Connection, userId: UUID, revoked: Boolean) {
        val token = UUID.randomUUID().toString().replace("-", "").repeat(2)
        connection.prepareStatement(
            """INSERT INTO device_registrations
               (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at,revoked_at)
               VALUES (?,?,'ANDROID_RUSTORE',?,?,clock_timestamp(),clock_timestamp(),
                       CASE WHEN ? THEN clock_timestamp() ELSE NULL END)"""
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, userId)
            statement.setString(3, "test-ciphertext")
            statement.setString(4, token)
            statement.setBoolean(5, revoked)
            statement.executeUpdate()
        }
    }

    private fun count(connection: Connection, table: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun execute(connection: Connection, sql: String, vararg values: UUID) {
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
}
