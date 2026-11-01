package com.example.notifications

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcNotificationWriter
import com.example.persistence.NotificationEventDraft
import com.example.persistence.NotificationEventType
import com.example.persistence.RecipientBarrier
import com.example.persistence.RecipientLockClaim
import com.example.persistence.RecipientLockMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NotificationServiceIntegrationTest {
    @Test
    fun `disabling extra waits for in flight event and cancels its pending delivery`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step7notifications$")))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val installation = UUID.randomUUID()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actor)
            connection.prepareStatement(
                "UPDATE notification_settings SET extra_push_enabled=true WHERE user_id=?"
            ).use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO device_registrations
                   (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at)
                   VALUES (?,?,'ANDROID_RUSTORE','encrypted',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, installation); statement.setObject(2, actor)
                statement.setString(3, UUID.randomUUID().toString().replace("-", "").repeat(2))
                statement.executeUpdate()
            }
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword,
            "notification-concurrency-secret-at-least-32-bytes", appEnvironment = AppEnvironment.LOCAL)).start()
        runtime.use { database ->
            val writerReady = CountDownLatch(1)
            val releaseWriter = CountDownLatch(1)
            val writerFailure = AtomicReference<Throwable?>()
            val writer = Thread {
                try {
                    DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                        connection.autoCommit = false
                        val barrier = RecipientBarrier.acquire(connection,
                            listOf(RecipientLockClaim(actor, RecipientLockMode.SHARED)))
                        JdbcNotificationWriter().write(connection, barrier, NotificationEventDraft(
                            actor, NotificationEventType.NEARBY_MEETING, "nearby:${UUID.randomUUID()}",
                            buildJsonObject { put("title", "Встреча рядом"); put("body", "Test") },
                            pointId = UUID.randomUUID(), proposalId = UUID.randomUUID(),
                        ))
                        writerReady.countDown()
                        check(releaseWriter.await(10, TimeUnit.SECONDS))
                        connection.commit()
                    }
                } catch (failure: Throwable) {
                    writerFailure.set(failure)
                    writerReady.countDown()
                }
            }
            writer.start()
            assertTrue(writerReady.await(10, TimeUnit.SECONDS))
            try {
                val service = NotificationService(database, "notification-concurrency-secret-at-least-32-bytes")
                val update = async(Dispatchers.IO) { service.updateSettings(actor,
                    NotificationSettingsChange(true, true, false, false, 0)) }
                delay(100)
                releaseWriter.countDown()
                assertIs<SettingsUpdateResult.Updated>(update.await())
                writer.join(10_000)
                assertTrue(!writer.isAlive)
                writerFailure.get()?.let { throw it }
                DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                    connection.prepareStatement(
                        """SELECT count(*) FROM push_delivery_jobs AS job
                           JOIN notification_events AS event ON event.id=job.notification_event_id
                           WHERE event.recipient_user_id=? AND event.channel='EXTRA' AND job.status='PENDING'"""
                    ).use { statement ->
                        statement.setObject(1, actor)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                    }
                }
            } finally {
                releaseWriter.countDown()
                writer.join(10_000)
            }
        }
    }

    @Test
    fun `settings location and visible center obey account preferences`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step7notifications$")))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val visible = UUID.randomUUID()
        val pushOnly = UUID.randomUUID()
        val installation = UUID.randomUUID()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actor)
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword,
            "notification-test-secret-at-least-32-bytes", appEnvironment = AppEnvironment.LOCAL)).start()
        runtime.use { database ->
            val service = NotificationService(database, "notification-test-secret-at-least-32-bytes", "test")
            val defaults = assertNotNull(service.settings(actor))
            assertEquals(0, defaults.revision)
            assertTrue(defaults.remindersPushEnabled && defaults.personalPushEnabled)
            assertTrue(!defaults.extraPushEnabled && !defaults.extraInAppEnabled)
            assertIs<LocationWriteResult.DisabledBySettings>(service.setLocation(actor, 55.75, 37.62))

            val enabled = assertIs<SettingsUpdateResult.Updated>(service.updateSettings(actor,
                NotificationSettingsChange(true, true, true, false, 0))).settings
            assertEquals(1, enabled.revision)
            assertIs<LocationWriteResult.Updated>(service.setLocation(actor, 55.75, 37.62))
            assertEquals(55.75, service.location(actor).latitude)
            DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                connection.autoCommit = false
                insertEvent(connection, visible, actor, "PERSONAL", "chat_message", true, "visible", UUID.randomUUID())
                insertEvent(connection, pushOnly, actor, "EXTRA", "nearby_meeting", false, "hidden", null)
                connection.prepareStatement(
                    """INSERT INTO device_registrations
                       (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at)
                       VALUES (?,?,'ANDROID_RUSTORE','encrypted',?,clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, installation); statement.setObject(2, actor)
                    statement.setString(3, UUID.randomUUID().toString().replace("-", "").repeat(2))
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """INSERT INTO push_delivery_jobs (id,notification_event_id,installation_id,next_attempt_at)
                       VALUES (?,?,?,clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID()); statement.setObject(2, pushOnly)
                    statement.setObject(3, installation); statement.executeUpdate()
                }
                connection.commit()
            }
            val page = service.list(actor, null, 1)
            assertEquals(listOf(visible), page.items.map { it.id })
            assertNull(page.nextCursor)
            assertNull(service.markRead(actor, pushOnly))
            val read = assertNotNull(service.markRead(actor, visible))
            assertNotNull(read.readAt)
            assertEquals(read.readAt, service.markRead(actor, visible)?.readAt)
            assertIs<SettingsUpdateResult.Stale>(service.updateSettings(actor,
                NotificationSettingsChange(true, true, false, false, 0)))
            val disabled = assertIs<SettingsUpdateResult.Updated>(service.updateSettings(actor,
                NotificationSettingsChange(true, true, false, false, 1))).settings
            assertEquals(2, disabled.revision)
            assertNull(service.location(actor).latitude)
            val newerPolicy = NotificationService(database, "notification-test-secret-at-least-32-bytes", "newer")
            assertIs<LocationWriteResult.PolicyNotAccepted>(newerPolicy.setLocation(actor, 55.75, 37.62))
            DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                connection.prepareStatement(
                    """SELECT status,completed_at FROM push_delivery_jobs WHERE notification_event_id=?"""
                ).use { statement ->
                    statement.setObject(1, pushOnly)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals("CANCELLED", rows.getString(1))
                        assertNotNull(rows.getObject(2))
                    }
                }
            }
            val productionGate = NotificationService(database, "notification-test-secret-at-least-32-bytes")
            assertIs<LocationWriteResult.PolicyUnavailable>(productionGate.setLocation(actor, 55.75, 37.62))
            assertNull(productionGate.location(actor).latitude)
            productionGate.deleteLocation(actor)
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, id); statement.executeUpdate() }
    }

    private fun insertEvent(connection: Connection, id: UUID, userId: UUID, channel: String, kind: String,
                            visible: Boolean, title: String, chatId: UUID?) {
        connection.prepareStatement(
            """INSERT INTO notification_events (id,recipient_user_id,channel,event_type,dedupe_key,chat_id,
               display_snapshot,visible_in_app,push_requested,created_at)
               VALUES (?,?,?,?,?,?,?,?,?,clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id); statement.setObject(2, userId); statement.setString(3, channel)
            statement.setString(4, kind); statement.setString(5, id.toString()); statement.setObject(6, chatId)
            statement.setObject(7, PGobject().apply { type = "jsonb"; value = "{\"title\":\"$title\",\"body\":\"Test\"}" })
            statement.setBoolean(8, visible); statement.setBoolean(9, !visible)
            statement.executeUpdate()
        }
    }
}
