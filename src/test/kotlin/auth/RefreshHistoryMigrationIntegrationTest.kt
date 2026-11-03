package com.example.auth

import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.UUID
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requires a fresh, separately provisioned database; never cleans an existing schema. */
class RefreshHistoryMigrationIntegrationTest {
    @Test
    fun `upgrade revokes rotated legacy sessions and preserves never rotated sessions`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_upgrade$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('public.flyway_schema_history') IS NULL").use {
                    check(it.next() && it.getBoolean(1)) { "Upgrade test requires a fresh database" }
                }
            }
        }
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
            .target("013").load().migrate()
        val userId = UUID.randomUUID()
        val rotatedId = UUID.randomUUID()
        val freshId = UUID.randomUUID()
        val oldestHash = RefreshTokens.hash("oldest")
        val previousHash = RefreshTokens.hash("previous")
        val currentHash = RefreshTokens.hash("current")
        val freshHash = RefreshTokens.hash("fresh")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,'+79990000001','test-only','Test',24,'test-only',now(),now(),now())"""
            ).use { it.setObject(1, userId); it.executeUpdate() }
            listOf(rotatedId to currentHash, freshId to freshHash).forEach { (id, hash) ->
                connection.prepareStatement(
                    """INSERT INTO auth_sessions (id,user_id,created_at,expires_at,auth_version,
                       refresh_token_hash,previous_refresh_token_hash)
                       VALUES (?,?,now(),now()+interval '1 hour',0,?,?)"""
                ).use {
                    it.setObject(1, id)
                    it.setObject(2, userId)
                    it.setString(3, hash)
                    it.setString(4, previousHash.takeIf { id == rotatedId })
                    it.executeUpdate()
                }
            }
        }
        // The oldest hash is deliberately absent, matching two pre-upgrade rotations.
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only")).start().use { runtime ->
            val repository = JdbcSessionRepository(runtime)
            val now = Instant.now()
            assertNull(repository.findActive(SessionIdentity(userId, rotatedId, 0), now))
            assertEquals(RefreshRotationResult.Invalid,
                repository.rotate(rotatedId, oldestHash, RefreshTokens.hash("unused"), now))
            assertEquals(RefreshRotationResult.Invalid,
                repository.rotate(rotatedId, currentHash, RefreshTokens.hash("unused"), now))
            assertNotNull(repository.findActive(SessionIdentity(userId, freshId, 0), now))
            assertIs<RefreshRotationResult.Rotated>(
                repository.rotate(freshId, freshHash, RefreshTokens.hash("fresh-next"), now))
            runtime.withJdbcTransaction { connection ->
                connection.prepareStatement(
                    "SELECT token_hash FROM auth_session_used_refresh_tokens WHERE session_id = ?"
                ).use {
                    it.setObject(1, rotatedId)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(previousHash, rows.getString(1))
                    }
                }
            }
        }
    }
}
