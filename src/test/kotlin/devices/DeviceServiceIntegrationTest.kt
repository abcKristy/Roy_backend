package com.example.devices

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceServiceIntegrationTest {
    @Test
    fun `registration encrypts token and rebind cancels old owner's queued jobs`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step7devices$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val firstUser = UUID.randomUUID()
        val secondUser = UUID.randomUUID()
        val installation = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, firstUser)
            seedUser(connection, secondUser)
            connection.commit()
        }
        val cipher = DeviceTokenCipher.fromBase64(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        assertFails { cipher.decrypt(installation, "IOS_APNS", cipher.encrypt(installation, "ANDROID_RUSTORE", "token")) }
        val database = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "device-integration-secret-at-least-32-bytes", appEnvironment = AppEnvironment.LOCAL)).start()
        try {
            val service = DeviceService(database, cipher)
            val initial = assertIs<RegisterDeviceResult.Registered>(service.register(
                RegisterDeviceCommand(firstUser, installation, "ANDROID_RUSTORE", "token-first"))).value
            val again = assertIs<RegisterDeviceResult.Registered>(service.register(
                RegisterDeviceCommand(firstUser, installation, "ANDROID_RUSTORE", "token-first"))).value
            assertEquals(initial, again)
            val event = UUID.randomUUID()
            val job = UUID.randomUUID()
            var ciphertext: String
            DriverManager.getConnection(url, user, password).use { connection ->
                ciphertext = connection.prepareStatement(
                    "SELECT token_ciphertext FROM device_registrations WHERE installation_id=?"
                ).use { statement ->
                    statement.setObject(1, installation)
                    statement.executeQuery().use { rows -> rows.next(); rows.getString(1) }
                }
                assertNotEquals("token-first", ciphertext)
                assertEquals("token-first", cipher.decrypt(installation, "ANDROID_RUSTORE", ciphertext))
                connection.prepareStatement(
                    """INSERT INTO notification_events
                       (id,recipient_user_id,channel,event_type,dedupe_key,display_snapshot,
                        visible_in_app,push_requested,created_at)
                       VALUES (?,?,'PERSONAL','chat_message',?, '{}'::jsonb,true,true,clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, event); statement.setObject(2, firstUser)
                    statement.setString(3, "device-test:$event"); statement.executeUpdate()
                }
                connection.prepareStatement(
                    """INSERT INTO push_delivery_jobs (id,notification_event_id,installation_id,next_attempt_at)
                       VALUES (?,?,?,clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, job); statement.setObject(2, event)
                    statement.setObject(3, installation); statement.executeUpdate()
                }
            }
            assertIs<RegisterDeviceResult.TokenInUse>(service.register(RegisterDeviceCommand(
                secondUser, UUID.randomUUID(), "ANDROID_RUSTORE", "token-first")))
            val rotated = assertIs<RegisterDeviceResult.Registered>(service.register(
                RegisterDeviceCommand(firstUser, installation, "ANDROID_RUSTORE", "token-rotated"))).value
            assertEquals(initial.registeredAt, rotated.registeredAt)
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement("SELECT token_ciphertext FROM device_registrations WHERE installation_id=?")
                    .use { statement ->
                        statement.setObject(1, installation)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals("token-rotated", cipher.decrypt(installation, "ANDROID_RUSTORE", rows.getString(1)))
                        }
                    }
            }
            val rebind = assertIs<RegisterDeviceResult.Registered>(service.register(
                RegisterDeviceCommand(secondUser, installation, "ANDROID_RUSTORE", "token-second"))).value
            assertTrue(!rebind.registeredAt.isBefore(initial.registeredAt))
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    "SELECT status,claim_token,lease_until,completed_at FROM push_delivery_jobs WHERE id=?"
                ).use { statement ->
                    statement.setObject(1, job)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals("CANCELLED", rows.getString(1))
                        assertNull(rows.getObject(2)); assertNull(rows.getObject(3))
                        assertTrue(rows.getObject(4) != null)
                    }
                }
                connection.prepareStatement(
                    "SELECT user_id,token_ciphertext FROM device_registrations WHERE installation_id=?"
                ).use { statement ->
                    statement.setObject(1, installation)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals(secondUser, rows.getObject(1, UUID::class.java))
                        ciphertext = rows.getString(2)
                    }
                }
                assertEquals("token-second", cipher.decrypt(installation, "ANDROID_RUSTORE", ciphertext))
            }
            service.revoke(firstUser, installation)
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement("SELECT revoked_at FROM device_registrations WHERE installation_id=?")
                    .use { statement -> statement.setObject(1, installation)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertNull(rows.getObject(1)) } }
            }
            service.revoke(secondUser, installation)
            service.revoke(secondUser, installation)
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement("SELECT revoked_at FROM device_registrations WHERE installation_id=?")
                    .use { statement -> statement.setObject(1, installation)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertTrue(rows.getObject(1) != null) } }
            }
        } finally {
            database.close()
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, id); statement.executeUpdate() }
    }
}
