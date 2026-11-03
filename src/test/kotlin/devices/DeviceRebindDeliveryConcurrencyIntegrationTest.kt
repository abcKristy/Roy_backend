package com.example.devices

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.jobs.PushDeliveryWorker
import com.example.jobs.PushSendResult
import com.example.jobs.PushSender
import com.example.jobs.PushTokenDecoder
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A rebind cancels the old claim; an already-started provider call may finish once. */
class DeviceRebindDeliveryConcurrencyIntegrationTest {
    @Test
    fun `rebind during provider call cancels claim without using new owners token`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step8delete$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val oldOwner = UUID.randomUUID(); val newOwner = UUID.randomUUID()
        val installation = UUID.randomUUID(); val point = UUID.randomUUID()
        val topic = UUID.randomUUID(); val chat = UUID.randomUUID()
        val event = UUID.randomUUID(); val job = UUID.randomUUID()
        val cipher = DeviceTokenCipher.fromBase64(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "test-secret", appEnvironment = AppEnvironment.LOCAL)).start()
        runtime.use { database ->
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.autoCommit = false
                seedUser(connection, oldOwner); seedUser(connection, newOwner)
                connection.prepareStatement(
                    """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37.8,55.8),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
                ).use { s -> s.setObject(1, point); s.setObject(2, oldOwner); s.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
                ).use { s -> s.setObject(1, topic); s.setObject(2, point); s.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())"
                ).use { s -> s.setObject(1, chat); s.setObject(2, topic); s.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
                ).use { s -> s.setObject(1, chat); s.setObject(2, oldOwner); s.executeUpdate() }
                connection.commit()
            }
            val devices = DeviceService(database, cipher)
            assertIs<RegisterDeviceResult.Registered>(devices.register(
                RegisterDeviceCommand(oldOwner, installation, "ANDROID_RUSTORE", "old-token")))
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.autoCommit = false
                connection.prepareStatement(
                    """INSERT INTO notification_events(id,recipient_user_id,channel,event_type,dedupe_key,
                       point_id,chat_id,display_snapshot,visible_in_app,push_requested,created_at)
                       VALUES (?,?,'PERSONAL','chat_message',?, ?,?,'{"title":"Test","body":"Test"}'::jsonb,
                       true,true,clock_timestamp())"""
                ).use { s ->
                    s.setObject(1, event); s.setObject(2, oldOwner); s.setString(3, "rebind:$event")
                    s.setObject(4, point); s.setObject(5, chat); s.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO push_delivery_jobs(id,notification_event_id,installation_id,next_attempt_at) VALUES (?,?,?,clock_timestamp())"
                ).use { s -> s.setObject(1, job); s.setObject(2, event); s.setObject(3, installation); s.executeUpdate() }
                connection.commit()
            }
            val providerEntered = CountDownLatch(1)
            val releaseProvider = CountDownLatch(1)
            val sentToken = AtomicReference<String?>()
            val worker = PushDeliveryWorker(database, PushSender { _, token, _ ->
                sentToken.set(token)
                providerEntered.countDown()
                check(releaseProvider.await(15, TimeUnit.SECONDS))
                PushSendResult.Delivered
            }, PushTokenDecoder { id, platform, encrypted -> cipher.decrypt(id, platform, encrypted) })
            val delivery = async(Dispatchers.IO) { worker.processBatch() }
            assertTrue(providerEntered.await(15, TimeUnit.SECONDS))
            try {
                assertIs<RegisterDeviceResult.Registered>(devices.register(
                    RegisterDeviceCommand(newOwner, installation, "ANDROID_RUSTORE", "new-token")))
            } finally {
                releaseProvider.countDown()
            }
            assertEquals(1, delivery.await())
            assertEquals("old-token", sentToken.get())
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    "SELECT status,completed_at FROM push_delivery_jobs WHERE id=?"
                ).use { s ->
                    s.setObject(1, job); s.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals("CANCELLED", rows.getString(1))
                        assertNotNull(rows.getObject(2))
                    }
                }
                connection.prepareStatement(
                    "SELECT user_id,platform,token_ciphertext FROM device_registrations WHERE installation_id=?"
                ).use { s ->
                    s.setObject(1, installation); s.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals(newOwner, rows.getObject(1, UUID::class.java))
                        assertEquals("new-token", cipher.decrypt(installation, rows.getString(2), rows.getString(3)))
                    }
                }
            }
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'hash','Test',20,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { s -> s.setObject(1, id); s.setString(2, phone); s.executeUpdate() }
        connection.prepareStatement(
            "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())"
        ).use { s -> s.setObject(1, id); s.executeUpdate() }
    }
}
