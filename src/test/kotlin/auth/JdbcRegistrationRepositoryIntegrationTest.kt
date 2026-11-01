package com.example.auth

import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcRegistrationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Run against a disposable database whose name contains _m1t2_; never point at roy. */
class JdbcRegistrationRepositoryIntegrationTest {
    @Test
    fun `transaction unique race and rollback use isolated postgres`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url)) {
            "TEST_DATABASE_URL must point to a disposable roy_m1t2_ database"
        }
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret-at-least-thirty-two-bytes-long")).start()
        try {
            val repository = JdbcRegistrationRepository(runtime)
            val first = registration("+79990000001")
            repository.create(first)
            assertEquals(1, count(url, user, password, "users"))
            assertEquals(1, count(url, user, password, "auth_sessions"))
            assertEquals(1, count(url, user, password, "notification_settings"))

            val duplicateSession = registration("+79990000002").copy(sessionId = first.sessionId)
            assertFailsWith<SQLException> { repository.create(duplicateSession) }
            assertEquals(1, count(url, user, password, "users"))
            assertEquals(1, count(url, user, password, "auth_sessions"))
            assertEquals(1, count(url, user, password, "notification_settings"))

            val race = listOf(registration("+79990000003"), registration("+79990000003"))
            val results = race.map { value -> async {
                runCatching { repository.create(value) }.exceptionOrNull()
            } }.awaitAll()
            assertEquals(1, results.count { it == null })
            assertEquals(1, results.count { it is DuplicatePhoneException })
            assertEquals(2, count(url, user, password, "users"))
            assertEquals(2, count(url, user, password, "auth_sessions"))
            assertEquals(2, count(url, user, password, "notification_settings"))
        } finally {
            runtime.close()
        }
    }

    private fun registration(phone: String): NewRegistration {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        return NewRegistration(
            userId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            phone = phone,
            passwordHash = "test-hash",
            displayName = "Test",
            ageYears = 24,
            privacyVersion = "test-only-v1",
            createdAt = now,
            expiresAt = now.plusSeconds(30L * 24 * 60 * 60),
        )
    }

    private fun count(url: String, user: String, password: String, table: String): Int {
        assertTrue(table in setOf("users", "auth_sessions", "notification_settings"))
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { result ->
                    result.next()
                    return result.getInt(1)
                }
            }
        }
    }
}
