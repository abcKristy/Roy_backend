package com.example.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerConfigTest {
    private val required = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://127.0.0.1:5432/roy",
        "DATABASE_USER" to "test",
        "DATABASE_PASSWORD" to "test",
        "JWT_SECRET" to "test-only-secret-at-least-thirty-two-bytes-long",
    )

    @Test
    fun `session ttl defaults to thirty days and can be shortened`() {
        assertEquals(Duration.ofDays(30), ServerConfig.fromEnvironment(required).jwtTtl)
        assertEquals(Duration.ofHours(1), ServerConfig.fromEnvironment(required + ("JWT_TTL_SECONDS" to "3600")).jwtTtl)
    }

    @Test
    fun `invalid or excessive session ttl is rejected`() {
        for (value in listOf("", "abc", "59", "2592001", "-1")) {
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(required + ("JWT_TTL_SECONDS" to value))
            }
        }
    }
}
