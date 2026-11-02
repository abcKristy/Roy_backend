package com.example.config

import java.time.Duration
import com.example.config.AppEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigTest {
    private val required = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://127.0.0.1:5432/roy",
        "DATABASE_USER" to "test",
        "DATABASE_PASSWORD" to "test",
        "JWT_SECRET" to "test-only-secret-at-least-thirty-two-bytes-long",
    )

    @Test
    fun `database settings validate bounds and migration credential pairs`() {
        val configured = ServerConfig.fromEnvironment(required + mapOf(
            "MIGRATION_DATABASE_USER" to "migrator",
            "MIGRATION_DATABASE_PASSWORD" to "secret",
            "DATABASE_MAX_POOL_SIZE" to "8",
            "DATABASE_CONNECTION_TIMEOUT_MS" to "800",
            "DATABASE_STATEMENT_TIMEOUT_MS" to "1200",
            "DATABASE_LOCK_TIMEOUT_MS" to "300",
        ))
        assertEquals("migrator", configured.migrationUser)
        assertEquals(8, configured.databaseMaxPoolSize)
        assertEquals(800, configured.databaseConnectionTimeoutMs)
        assertEquals(1200, configured.databaseStatementTimeoutMs)
        assertEquals(300, configured.databaseLockTimeoutMs)
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + ("MIGRATION_DATABASE_USER" to "migrator"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + ("DATABASE_LOCK_TIMEOUT_MS" to "5000"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + ("DATABASE_MAX_POOL_SIZE" to "0"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + ("DATABASE_CONNECTION_TIMEOUT_MS" to "499"))
        }
        assertEquals(AppEnvironment.PRODUCTION, ServerConfig.fromEnvironment(required).appEnvironment)
    }

    @Test
    fun `access and refresh ttl have independent defaults and overrides`() {
        assertEquals(Duration.ofMinutes(15), ServerConfig.fromEnvironment(required).jwtTtl)
        assertEquals(Duration.ofDays(30), ServerConfig.fromEnvironment(required).refreshTtl)
        assertEquals(Duration.ofHours(1), ServerConfig.fromEnvironment(required + ("JWT_TTL_SECONDS" to "3600")).jwtTtl)
        assertEquals(Duration.ofDays(7), ServerConfig.fromEnvironment(required + ("REFRESH_TTL_SECONDS" to "604800")).refreshTtl)
    }

    @Test
    fun `invalid or excessive session ttl is rejected`() {
        for (value in listOf("", "abc", "59", "2592001", "-1")) {
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(required + ("JWT_TTL_SECONDS" to value))
            }
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(required + ("REFRESH_TTL_SECONDS" to value))
            }
        }
    }

    @Test
    fun `draft privacy is disabled by default and needs explicit local configuration`() {
        assertFalse(ServerConfig.fromEnvironment(required).draftPrivacyEnabled)
        val local = ServerConfig.fromEnvironment(required + mapOf(
            "APP_ENV" to "local",
            "AUTH_DRAFT_POLICY_ENABLED" to "true",
            "PUBLIC_BASE_URL" to "http://127.0.0.1:8080",
        ))
        assertTrue(local.draftPrivacyEnabled)
        assertEquals("http://127.0.0.1:8080", local.publicBaseUrl.toString())
    }

    @Test
    fun `production and malformed draft configuration fail fast`() {
        val local = mapOf(
            "AUTH_DRAFT_POLICY_ENABLED" to "true",
            "PUBLIC_BASE_URL" to "http://127.0.0.1:8080",
        )
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + local + ("APP_ENV" to "production"))
        }
        for (url in listOf("http://example.test/path", "http://example.test/?x=1", "ftp://example.test", "http://user@example.test")) {
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(required + local + ("PUBLIC_BASE_URL" to url))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + ("AUTH_DRAFT_POLICY_ENABLED" to "true"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + mapOf(
                "AUTH_DRAFT_POLICY_ENABLED" to "true",
                "PUBLIC_BASE_URL" to "http://127.0.0.1:8080",
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + local + ("PUBLIC_BASE_URL" to "https://draft.example.test"))
        }
        for (host in listOf("0.0.0.0", "192.168.1.5", "example.test")) {
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(required + local + mapOf("APP_ENV" to "local", "SERVER_HOST" to host))
            }
        }
        assertFalse(ServerConfig.fromEnvironment(required + ("PUBLIC_BASE_URL" to "")).draftPrivacyEnabled)
    }
}
