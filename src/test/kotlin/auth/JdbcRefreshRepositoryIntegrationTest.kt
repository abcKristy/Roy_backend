package com.example.auth

import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcRegistrationRepository
import com.example.persistence.JdbcSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Runs only against a disposable database explicitly named roy_m1t2_*. */
class JdbcRefreshRepositoryIntegrationTest {
    @Test
    fun `rotation preserves fixed expiry and replay revokes only its session`() = runBlocking {
        val runtime = openDisposableDatabase() ?: return@runBlocking
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val expiry = now.plusSeconds(3600)
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val hashA = hash(sessionId, "a")
        val hashB = hash(sessionId, "b")
        val hashC = hash(sessionId, "c")
        val otherSessionId = UUID.randomUUID()
        val otherHash = hash(otherSessionId, "other")
        try {
            JdbcRegistrationRepository(runtime).create(NewRegistration(
                userId, sessionId, uniquePhone(), "test-hash", "Аня", 24,
                "test-only", now, expiry, hashA,
            ))
            val repository = JdbcSessionRepository(runtime)
            val identity = SessionIdentity(userId, sessionId, 0)
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """INSERT INTO auth_sessions
                       (id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                       VALUES (?,?,?,?,0,?)"""
                ).use { statement ->
                    statement.setObject(1, otherSessionId)
                    statement.setObject(2, userId)
                    statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(4, expiry.atOffset(ZoneOffset.UTC))
                    statement.setString(5, otherHash)
                    statement.executeUpdate()
                }
            }
            val otherIdentity = SessionIdentity(userId, otherSessionId, 0)
            val signingSecret = "test-only-secret-at-least-thirty-two-bytes-long"
            val access = SessionAccessService(signingSecret, repository, Clock.fixed(now, ZoneOffset.UTC))
            val accessToken = JwtTokenIssuer(signingSecret).issue(userId, sessionId, 0, now, expiry)
            val otherAccessToken = JwtTokenIssuer(signingSecret).issue(userId, otherSessionId, 0, now, expiry)
            assertEquals(userId.toString(), access.currentUser("Bearer $accessToken").id)
            assertEquals(RefreshRotationResult.Invalid, repository.rotate(sessionId, hash(sessionId, "unknown"), hashB, now))
            assertNotNull(repository.findActive(identity, now))

            val rotated = assertIs<RefreshRotationResult.Rotated>(repository.rotate(sessionId, hashA, hashB, now))
            assertEquals(expiry, rotated.sessionExpiresAt)
            assertEquals(0, rotated.authVersion)
            assertEquals(userId, rotated.userId)
            val rotatedAgain = assertIs<RefreshRotationResult.Rotated>(
                repository.rotate(sessionId, hashB, hashC, now.plusSeconds(1))
            )
            assertEquals(expiry, rotatedAgain.sessionExpiresAt)
            assertNotNull(repository.findActive(identity, now.plusSeconds(1)))
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM auth_session_used_refresh_tokens WHERE session_id = ?"
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(2, rows.getInt(1))
                    }
                }
            }
            assertEquals(RefreshRotationResult.Replayed, repository.rotate(sessionId, hashA, hash(sessionId, "d"), now))
            assertNull(repository.findActive(identity, now))
            assertEquals("SESSION_EXPIRED", assertFailsWith<com.example.api.ApiException> {
                access.currentUser("Bearer $accessToken")
            }.code)
            assertEquals(RefreshRotationResult.Invalid, repository.rotate(sessionId, hashC, hash(sessionId, "e"), now))
            assertNotNull(repository.findActive(otherIdentity, now))
            assertEquals(userId.toString(), access.currentUser("Bearer $otherAccessToken").id)
            val otherRotated = assertIs<RefreshRotationResult.Rotated>(
                repository.rotate(otherSessionId, otherHash, hash(otherSessionId, "next"), now)
            )
            assertEquals(expiry, otherRotated.sessionExpiresAt)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `version change expiry deletion and legacy sessions cannot refresh`() = runBlocking {
        val runtime = openDisposableDatabase() ?: return@runBlocking
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val legacyId = UUID.randomUUID()
        try {
            JdbcRegistrationRepository(runtime).create(NewRegistration(
                userId, sessionId, uniquePhone(), "test-hash", "Аня", 24,
                "test-only", now, now.plusSeconds(3600), hash(sessionId, "initial"),
            ))
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO auth_sessions (id,user_id,created_at,expires_at) VALUES (?,?,?,?)"
                ).use { statement ->
                    statement.setObject(1, legacyId)
                    statement.setObject(2, userId)
                    statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(4, now.plusSeconds(3600).atOffset(ZoneOffset.UTC))
                    statement.executeUpdate()
                }
            }
            val repository = JdbcSessionRepository(runtime)
            assertEquals(RefreshRotationResult.Invalid, repository.rotate(legacyId, hash(legacyId, "unknown"), hash(legacyId, "next"), now))
            assertEquals(RefreshRotationResult.Invalid, repository.rotate(sessionId, hash(sessionId, "initial"), hash(sessionId, "next"), now.plusSeconds(3600)))
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement("UPDATE users SET auth_version = auth_version + 1 WHERE id = ?").use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            }
            assertEquals(RefreshRotationResult.Invalid, repository.rotate(sessionId, hash(sessionId, "initial"), hash(sessionId, "next"), now))
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """UPDATE users SET auth_version = 0, deleted_at = ?, phone_e164 = NULL,
                       password_hash = NULL, display_name = NULL, age_years = NULL,
                       accepted_privacy_version = NULL, accepted_privacy_at = NULL
                       WHERE id = ?"""
                ).use { statement ->
                    statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
            }
            assertEquals(RefreshRotationResult.Invalid, repository.rotate(sessionId, hash(sessionId, "initial"), hash(sessionId, "next"), now))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `concurrent reuse rotates once then revokes session`() = runBlocking {
        val runtime = openDisposableDatabase() ?: return@runBlocking
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        try {
            JdbcRegistrationRepository(runtime).create(NewRegistration(
                userId, sessionId, uniquePhone(), "test-hash", "Аня", 24,
                "test-only", now, now.plusSeconds(3600), hash(sessionId, "initial"),
            ))
            val repository = JdbcSessionRepository(runtime)
            val outcomes = listOf("next-1", "next-2").map { next ->
                async(Dispatchers.IO) { repository.rotate(sessionId, hash(sessionId, "initial"), hash(sessionId, next), now) }
            }.awaitAll()
            assertEquals(1, outcomes.count { it is RefreshRotationResult.Rotated })
            assertEquals(1, outcomes.count { it == RefreshRotationResult.Replayed })
            assertNull(repository.findActive(SessionIdentity(userId, sessionId, 0), now))
        } finally {
            runtime.close()
        }
    }

    private fun uniquePhone(): String = "+79" + UUID.randomUUID().leastSignificantBits.toULong().toString().padStart(9, '0').take(9)

    private fun hash(sessionId: UUID, value: String): String = RefreshTokens.hash("$sessionId.$value")

    private fun openDisposableDatabase(): com.example.persistence.DatabaseRuntime? {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        return HikariDatabaseBootstrap(ServerConfig(
            url, user, password, "test-only-secret-at-least-thirty-two-bytes-long",
        )).start()
    }
}
