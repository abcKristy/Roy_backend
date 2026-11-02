package com.example.accounts

import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionIdentity
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

/** Verifies that an empty point and private rows disappear in the same deletion transaction. */
class AccountDeletionPrivateCleanupIntegrationTest {
    @Test
    fun `deletion removes empty point and private rows while preserving report tombstone`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step8delete$").matches(url))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val actor = UUID.randomUUID(); val peer = UUID.randomUUID(); val session = UUID.randomUUID()
        val point = UUID.randomUUID(); val topic = UUID.randomUUID(); val chat = UUID.randomUUID()
        val event = UUID.randomUUID(); val installation = UUID.randomUUID()
        val avatarKey = "avatars/${UUID.randomUUID()}.jpg"
        val hasher = Argon2idPasswordHasher()
        val hash = hasher.hash("correct-password")
        val phoneSuffix = ((actor.mostSignificantBits and Long.MAX_VALUE) % 900_000_000L + 100_000_000L).toString()
        HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, "test-secret",
            appEnvironment = AppEnvironment.LOCAL)).start().close()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            try {
                for ((id, phone) in listOf(actor to "+7$phoneSuffix", peer to "+8$phoneSuffix")) {
                    connection.execute(
                        """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,
                           avatar_object_key,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                           VALUES (?,?,?,'Test',20,?,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())""",
                        id, phone, hash, if (id == actor) avatarKey else null,
                    )
                }
                connection.execute(
                    """INSERT INTO auth_sessions(id,user_id,created_at,expires_at)
                       VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day')""", session, actor,
                )
                connection.execute(
                    """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37.6,55.8),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())""",
                    point, actor,
                )
                connection.execute("INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                connection.execute("INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                connection.execute("INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, actor)
                connection.execute("INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", actor)
                connection.execute(
                    """INSERT INTO notification_locations(user_id,position,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37.6,55.8),4326)::geography,clock_timestamp())""", actor,
                )
                connection.execute(
                    """INSERT INTO device_registrations(installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at)
                       VALUES (?,?,'ANDROID_RUSTORE','encrypted',?,clock_timestamp(),clock_timestamp())""",
                    installation, actor, "a".repeat(64),
                )
                connection.execute(
                    """INSERT INTO notification_events(id,recipient_user_id,channel,event_type,dedupe_key,
                       display_snapshot,visible_in_app,push_requested,created_at)
                       VALUES (?,?,'PERSONAL','chat_message',?,'{}'::jsonb,true,true,clock_timestamp())""",
                    event, actor, "test:${UUID.randomUUID()}",
                )
                connection.execute(
                    """INSERT INTO push_delivery_jobs(id,notification_event_id,installation_id,status,next_attempt_at)
                       VALUES (?,?,?,'PENDING',clock_timestamp())""", UUID.randomUUID(), event, installation,
                )
                connection.execute(
                    """INSERT INTO reports(id,reporter_user_id,target_user_id,context_chat_id,reason_text,created_at)
                       VALUES (?,?,?,?,?,clock_timestamp())""", UUID.randomUUID(), actor, peer, chat, "Sensitive reason",
                )
                connection.execute("INSERT INTO point_creation_events(id,creator_user_id,point_id,created_at) VALUES (?,?,?,clock_timestamp())",
                    UUID.randomUUID(), actor, point)
                connection.execute(
                    """INSERT INTO operation_idempotency(actor_user_id,operation_type,idempotency_key,request_hash,
                       outcome,created_at) VALUES (?,'test',?,?,'SUCCESS',clock_timestamp())""",
                    actor, UUID.randomUUID().toString(), "hash",
                )
                connection.execute("INSERT INTO message_rate_events(id,user_id,accepted_at) VALUES (?,?,clock_timestamp())",
                    UUID.randomUUID(), actor)
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, "test-secret",
            appEnvironment = AppEnvironment.LOCAL)).start()
        runtime.use {
            val result = AccountDeletionService(it, PasswordHashingWork(hasher))
                .delete(SessionIdentity(actor, session, 0), "correct-password")
            assertIs<AccountDeletionResult.Deleted>(result)
            assertEquals(listOf(point), result.removedPoints.map { it.pointId })
        }
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            for ((table, column, id) in listOf(
                Triple("points", "id", point),
                Triple("chat_memberships", "user_id", actor),
                Triple("notification_events", "recipient_user_id", actor),
                Triple("notification_settings", "user_id", actor),
                Triple("notification_locations", "user_id", actor),
                Triple("device_registrations", "user_id", actor),
                Triple("point_creation_events", "creator_user_id", actor),
                Triple("operation_idempotency", "actor_user_id", actor),
                Triple("message_rate_events", "user_id", actor),
                Triple("push_delivery_jobs", "notification_event_id", event),
            )) assertEquals(0, connection.count(table, column, id), table)
            assertEquals(1, connection.count("reports", "reporter_user_id", actor))
            connection.prepareStatement("SELECT reason_text,context_chat_id FROM reports WHERE reporter_user_id=?").use { statement ->
                statement.setObject(1, actor)
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertEquals("Причина скрыта: аккаунт удалён", rows.getString(1))
                    assertEquals(null, rows.getObject(2))
                }
            }
            connection.prepareStatement("SELECT status FROM media_deletion_jobs WHERE object_key=?").use { statement ->
                statement.setString(1, avatarKey)
                statement.executeQuery().use { rows -> rows.next(); assertEquals("PENDING", rows.getString(1)) }
            }
        }
    }

    private fun Connection.execute(sql: String, vararg args: Any?) = prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    private fun Connection.count(table: String, column: String, id: UUID): Int {
        check(table in setOf("points", "chat_memberships", "notification_events", "notification_settings",
            "notification_locations", "device_registrations", "point_creation_events", "operation_idempotency",
            "message_rate_events", "push_delivery_jobs", "reports"))
        return prepareStatement("SELECT count(*) FROM $table WHERE $column=?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }
}
