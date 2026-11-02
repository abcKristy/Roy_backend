package com.example.accounts

import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionIdentity
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcNotificationWriter
import com.example.persistence.NotificationEventDraft
import com.example.persistence.NotificationEventType
import com.example.persistence.NotificationWriteResult
import com.example.persistence.RecipientBarrier
import com.example.persistence.RecipientLockClaim
import com.example.persistence.RecipientLockMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The recipient barrier must serialize a committed fanout with private-state erasure. */
class AccountDeletionFanoutConcurrencyIntegrationTest {
    @Test
    fun `deletion waits for in flight notification then removes it`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step8delete$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val hasher = Argon2idPasswordHasher()
        val hash = hasher.hash("correct-password")
        val phone = "+7" + ((actor.mostSignificantBits and Long.MAX_VALUE) % 900_000_000L + 100_000_000L)
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret",
            appEnvironment = AppEnvironment.LOCAL)).start()
        runtime.use { database ->
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.autoCommit = false
                connection.prepareStatement(
                    """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,?,'Test',20,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, actor); statement.setString(2, phone)
                    statement.setString(3, hash); statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())"
                ).use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
                connection.prepareStatement(
                    """INSERT INTO auth_sessions(id,user_id,created_at,expires_at)
                       VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day')"""
                ).use { statement ->
                    statement.setObject(1, session); statement.setObject(2, actor); statement.executeUpdate()
                }
                connection.commit()
            }

            val writerReady = CountDownLatch(1)
            val releaseWriter = CountDownLatch(1)
            val writerFailure = AtomicReference<Throwable?>()
            val writer = Thread {
                try {
                    DriverManager.getConnection(url, user, password).use { connection ->
                        connection.autoCommit = false
                        val barrier = RecipientBarrier.acquire(connection,
                            listOf(RecipientLockClaim(actor, RecipientLockMode.SHARED)))
                        val created = assertIs<NotificationWriteResult.Created>(
                            JdbcNotificationWriter().write(connection, barrier, NotificationEventDraft(
                            actor, NotificationEventType.CHAT_MESSAGE, "concurrent:${UUID.randomUUID()}",
                            buildJsonObject { put("title", "Новое сообщение"); put("body", "Test") },
                            chatId = UUID.randomUUID(),
                        )))
                        connection.prepareStatement("SELECT 1 FROM notification_events WHERE id=?").use { statement ->
                            statement.setObject(1, created.eventId)
                            statement.executeQuery().use { rows -> assertTrue(rows.next()) }
                        }
                        writerReady.countDown()
                        check(releaseWriter.await(15, TimeUnit.SECONDS))
                        connection.commit()
                    }
                } catch (failure: Throwable) {
                    writerFailure.set(failure)
                    writerReady.countDown()
                }
            }
            writer.start()
            assertTrue(writerReady.await(15, TimeUnit.SECONDS))
            try {
                val deletion = async(Dispatchers.IO) {
                    AccountDeletionService(database, PasswordHashingWork(hasher))
                        .delete(SessionIdentity(actor, session, 0), "correct-password")
                }
                delay(200)
                assertTrue(!deletion.isCompleted)
                releaseWriter.countDown()
                assertIs<AccountDeletionResult.Deleted>(deletion.await())
                writer.join(15_000)
                assertTrue(!writer.isAlive)
                writerFailure.get()?.let { throw it }
                DriverManager.getConnection(url, user, password).use { connection ->
                    connection.prepareStatement(
                        "SELECT count(*) FROM notification_events WHERE recipient_user_id=?"
                    ).use { statement ->
                        statement.setObject(1, actor)
                        statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                    }
                }
            } finally {
                releaseWriter.countDown()
                writer.join(15_000)
            }
        }
    }
}
