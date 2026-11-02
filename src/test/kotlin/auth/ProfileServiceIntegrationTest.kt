package com.example.auth

import com.example.api.ApiException
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileServiceIntegrationTest {
    @Test
    fun `name update and password change revoke all sessions atomically`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url))
        val runtime = HikariDatabaseBootstrap(ServerConfig(url,
            System.getenv("DATABASE_USER") ?: error("DATABASE_USER required"),
            System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required"),
            "test-only-secret-at-least-thirty-two-bytes-long",
        )).start()
        val hashing = PasswordHashingWork(Argon2idPasswordHasher())
        val service = ProfileService(runtime, hashing)
        val userId = UUID.randomUUID()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val identity = SessionIdentity(userId, firstId, 0)
        val oldHash = hashing.hash("old password 123")
        val now = Instant.now()
        try {
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,?,?,?,?,?,?,?)"""
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, "+7${(1000000000..1999999999).random()}")
                    statement.setString(3, oldHash)
                    statement.setString(4, "Аня")
                    statement.setInt(5, 24)
                    statement.setString(6, "test-only")
                    statement.setObject(7, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(8, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(9, now.atOffset(ZoneOffset.UTC))
                    statement.executeUpdate()
                }
                for (id in listOf(firstId, secondId)) connection.prepareStatement(
                    """INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,
                       refresh_token_hash) VALUES (?,?,?,?,0,?)"""
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, userId)
                    statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(4, now.plusSeconds(3600).atOffset(ZoneOffset.UTC))
                    statement.setString(5, UUID.randomUUID().toString().replace("-", "").repeat(2))
                    statement.executeUpdate()
                }
            }
            assertEquals("Новое имя", service.updateName(identity, UpdateProfileRequest(" Новое имя ")).displayName)
            assertEquals("INVALID_CURRENT_PASSWORD", assertFailsWith<ApiException> {
                service.changePassword(identity, ChangePasswordRequest("wrong password", "new password 123"))
            }.code)
            val sessions = service.changePassword(identity,
                ChangePasswordRequest("old password 123", "new password 123"))
            assertEquals(setOf(firstId, secondId), sessions.toSet())
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement("SELECT password_hash,auth_version FROM users WHERE id=?").use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertTrue(Argon2idPasswordHasher().verify("new password 123", rows.getString(1)))
                        assertEquals(1, rows.getInt(2))
                    }
                }
                connection.prepareStatement(
                    "SELECT count(*) FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL"
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                }
            }
            assertEquals("SESSION_EXPIRED", assertFailsWith<ApiException> {
                service.updateName(identity, UpdateProfileRequest("Ещё имя"))
            }.code)
        } finally {
            runtime.close()
        }
    }
}
