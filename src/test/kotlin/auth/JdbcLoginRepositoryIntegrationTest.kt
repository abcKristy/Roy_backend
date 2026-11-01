package com.example.auth

import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcLoginRepository
import com.example.persistence.JdbcRegistrationRepository
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Runs only against a disposable database explicitly named roy_m1t2_*. */
class JdbcLoginRepositoryIntegrationTest {
    @Test
    fun `login session and rehash are atomic and stale credential is rejected`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url)) {
            "TEST_DATABASE_URL must point to a disposable roy_m1t2_ database"
        }
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val runtime = HikariDatabaseBootstrap(
            ServerConfig(url, user, password, "test-only-secret-at-least-thirty-two-bytes-long")
        ).start()
        val userId = UUID.randomUUID()
        val now = Instant.parse("2026-11-01T12:00:00Z")
        try {
            JdbcRegistrationRepository(runtime).create(
                NewRegistration(
                    userId, UUID.randomUUID(), "+79990000011", "old-hash", "Аня", 24,
                    "test-only-v1", now, now.plusSeconds(30L * 24 * 60 * 60),
                )
            )
            val repository = JdbcLoginRepository(runtime)
            val credential = assertNotNull(repository.findCredential("+79990000011"))
            assertEquals("old-hash", credential.passwordHash)
            val sessionId = UUID.randomUUID()
            val accepted = repository.createSession(
                NewLoginSession(userId, sessionId, "old-hash", 0, "new-hash", now, now.plusSeconds(3600))
            )
            assertNotNull(accepted)
            assertEquals("+79990000011", accepted.phone)
            assertEquals("new-hash", repository.findCredential("+79990000011")?.passwordHash)
            assertEquals(1, countSession(url, user, password, sessionId))

            val staleSessionId = UUID.randomUUID()
            assertNull(repository.createSession(
                NewLoginSession(userId, staleSessionId, "old-hash", 0, null, now, now.plusSeconds(3600))
            ))
            assertEquals(0, countSession(url, user, password, staleSessionId))
        } finally {
            runtime.close()
        }
    }

    private fun countSession(url: String, user: String, password: String, id: UUID): Int =
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement("SELECT count(*) FROM auth_sessions WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
}
