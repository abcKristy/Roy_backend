package com.example.auth

import com.example.api.ApiException
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileServiceIntegrationTest {
    @Test
    fun `password change from a legacy session revokes every access and refresh session`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
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
        val refreshHash = UUID.randomUUID().toString().replace("-", "").repeat(2)
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
                       refresh_token_hash) VALUES (?,?,?,?,?,?)"""
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, userId)
                    statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(4, now.plusSeconds(3600).atOffset(ZoneOffset.UTC))
                    statement.setObject(5, if (id == firstId) null else 0)
                    statement.setString(6, if (id == firstId) null else refreshHash)
                    statement.executeUpdate()
                }
            }
            val repository = JdbcSessionRepository(runtime)
            val modernIdentity = SessionIdentity(userId, secondId, 0)
            val wrongPassword = assertFailsWith<ApiException> {
                service.changePassword(modernIdentity, ChangePasswordRequest("wrong password", "new password 123"))
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, wrongPassword.status)
            assertEquals("INVALID_CURRENT_PASSWORD", wrongPassword.code)
            assertNotNull(repository.findActive(modernIdentity, now))
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
            for (expired in listOf(identity, modernIdentity)) {
                assertEquals(null, repository.findActive(expired, now))
                assertEquals("SESSION_EXPIRED", assertFailsWith<ApiException> {
                    service.changePassword(expired, ChangePasswordRequest("new password 123", "another password 123"))
                }.code)
            }
            assertEquals(
                RefreshRotationResult.Invalid,
                repository.rotate(secondId, refreshHash, "a".repeat(64), now),
            )
        } finally {
            runtime.close()
        }
    }
}
