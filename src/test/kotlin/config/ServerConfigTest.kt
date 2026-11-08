package com.example.config

import java.time.Duration
import com.example.config.AppEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun `push providers are optional complete production settings`() {
        val rustore = mapOf(
            "RUSTORE_PROJECT_ID" to "123",
            "RUSTORE_SERVICE_TOKEN" to "fixture-token",
        )
        val apns = mapOf(
            "APNS_TEAM_ID" to "TEAM123456",
            "APNS_KEY_ID" to "KEY1234567",
            "APNS_PRIVATE_KEY_PEM" to "fixture-pem",
            "APNS_TOPIC" to "example.roy",
            "APNS_ENVIRONMENT" to "sandbox",
        )
        val enabled = required + ("PUSH_TOKEN_ENCRYPTION_KEY_BASE64" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        assertEquals("123", ServerConfig.fromEnvironment(enabled + rustore).ruStorePush?.projectId)
        assertEquals("example.roy", ServerConfig.fromEnvironment(enabled + apns).apnsPush?.topic)
        for (settings in listOf(rustore, apns)) {
            assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(required + settings) }
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(enabled + settings + ("APP_ENV" to "local"))
            }
            for (missing in settings.keys) {
                val failure = assertFailsWith<IllegalStateException> {
                    ServerConfig.fromEnvironment(enabled + (settings - missing))
                }
                assertFalse(failure.stackTraceToString().contains("fixture-"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(enabled + apns + ("APNS_ENVIRONMENT" to "other"))
        }
    }

    @Test
    fun `malformed public url reports only a safe setting name`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + ("PUBLIC_BASE_URL" to "https://private.example/secret value"))
        }
        assertEquals("PUBLIC_BASE_URL is invalid", failure.message)
        assertEquals(null, failure.cause)
        assertFalse(failure.stackTraceToString().contains("secret value"))
    }

    @Test
    fun `production storage is optional and requires complete private configuration`() {
        val avatar = mapOf(
            "AVATAR_STORAGE_BUCKET" to "private-test",
            "AVATAR_STORAGE_KMS_KEY_ID" to "kms-fixture",
            "AVATAR_STORAGE_ACCESS_KEY_ID" to "fixture-id",
            "AVATAR_STORAGE_SECRET_ACCESS_KEY" to "fixture-secret",
        )
        assertEquals(null, ServerConfig.fromEnvironment(required).avatarObjectStorage)
        val config = requireNotNull(ServerConfig.fromEnvironment(required + avatar).avatarObjectStorage)
        assertEquals("private-test", config.bucket)
        assertFalse(config.toString().contains("fixture-secret"))
        for (missing in avatar.keys) {
            val failure = assertFailsWith<IllegalStateException> {
                ServerConfig.fromEnvironment(required + (avatar - missing))
            }
            assertFalse(failure.stackTraceToString().contains("fixture-secret"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + avatar + ("AVATAR_STORAGE_BUCKET" to "https://example.test"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + avatar + ("APP_ENV" to "local"))
        }
    }

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

    @Test
    fun `production policy requires complete settings and an HTTPS origin`() {
        val publication = mapOf(
            "AUTH_PRIVACY_POLICY_FILE" to "policy-fixture.md",
            "AUTH_PRIVACY_POLICY_VERSION" to "2026-11-08.1",
            "AUTH_PRIVACY_POLICY_PUBLISHED_AT" to "2026-11-08T00:00:00Z",
        )
        val https = mapOf("PUBLIC_BASE_URL" to "https://policy.example.test/")
        assertEquals(null, ServerConfig.fromEnvironment(required + https).privacyPolicyPublication)
        for (missing in publication.keys) {
            assertFailsWith<IllegalStateException> {
                ServerConfig.fromEnvironment(required + https + (publication - missing))
            }
        }
        for (origin in listOf(null, "http://policy.example.test", "https://policy.example.test/legal")) {
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(required + publication + origin?.let { mapOf("PUBLIC_BASE_URL" to it) }.orEmpty())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(required + https + publication + ("APP_ENV" to "local"))
        }
        assertEquals("2026-11-08.1", ServerConfig.fromEnvironment(required + https + publication).privacyPolicyPublication?.version)
    }

    @Test
    fun `production policy rejects malformed or draft version metadata without echoing values`() {
        val publication = required + mapOf(
            "PUBLIC_BASE_URL" to "https://policy.example.test",
            "AUTH_PRIVACY_POLICY_FILE" to "policy-fixture.md",
            "AUTH_PRIVACY_POLICY_VERSION" to "2026-11-08.1",
            "AUTH_PRIVACY_POLICY_PUBLISHED_AT" to "2026-11-08T00:00:00Z",
        )
        val malformed = listOf(
            "AUTH_PRIVACY_POLICY_VERSION" to "Draft-test-version",
            "AUTH_PRIVACY_POLICY_VERSION" to "test\u0000version",
            "AUTH_PRIVACY_POLICY_PUBLISHED_AT" to "test-invalid-timestamp",
        )
        for ((name, value) in malformed) {
            val failure = assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(publication + (name to value))
            }
            assertFalse(failure.stackTraceToString().contains(value))
        }
    }
}
