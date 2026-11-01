package com.example.config

import java.time.Duration
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
