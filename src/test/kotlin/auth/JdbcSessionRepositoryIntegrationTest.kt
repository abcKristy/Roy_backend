package com.example.auth

import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs only against a disposable database with a guarded name. */
class JdbcSessionRepositoryIntegrationTest {
    @Test
    fun `revocation affects only one jti and version or deletion blocks access`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret-at-least-thirty-two-bytes-long")).start()
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val uid = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        try {
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,?,?,?,?,?,?,?)"""
                ).use { statement ->
                    statement.setObject(1, uid)
                    statement.setString(2, "+79990000001")
                    statement.setString(3, "test-hash")
                    statement.setString(4, "Аня")
                    statement.setInt(5, 24)
                    statement.setString(6, "test-only")
                    statement.setObject(7, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(8, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(9, now.atOffset(ZoneOffset.UTC))
                    statement.executeUpdate()
                }
                listOf(first, second).forEach { sid ->
                    connection.prepareStatement(
                        "INSERT INTO auth_sessions (id,user_id,created_at,expires_at) VALUES (?,?,?,?)"
                    ).use { statement ->
                        statement.setObject(1, sid)
                        statement.setObject(2, uid)
                        statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                        statement.setObject(4, now.plusSeconds(3600).atOffset(ZoneOffset.UTC))
                        statement.executeUpdate()
                    }
                }
            }
            val repository = JdbcSessionRepository(runtime)
            val firstIdentity = SessionIdentity(uid, first, 0)
            val secondIdentity = SessionIdentity(uid, second, 0)
            assertNotNull(repository.findActive(firstIdentity, now))
            assertTrue(repository.revoke(firstIdentity, now))
            assertFalse(repository.revoke(firstIdentity, now))
            assertNull(repository.findActive(firstIdentity, now))
            assertNotNull(repository.findActive(secondIdentity, now))
            assertNull(repository.findActive(secondIdentity, now.plusSeconds(3600)))
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement("UPDATE users SET auth_version = 1 WHERE id = ?").use { statement ->
                    statement.setObject(1, uid)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertNull(repository.findActive(secondIdentity, now))
            assertFalse(repository.revoke(secondIdentity, now))
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    """UPDATE users SET deleted_at=?, phone_e164=NULL,password_hash=NULL,display_name=NULL,
                       age_years=NULL,accepted_privacy_version=NULL,accepted_privacy_at=NULL WHERE id=?"""
                ).use { statement ->
                    statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                    statement.setObject(2, uid)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertNull(repository.findActive(SessionIdentity(uid, second, 1), now))
        } finally {
            runtime.close()
        }
    }
}
