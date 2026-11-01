package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshRouteTest {
    @Test
    fun `refresh endpoint accepts token without bearer and returns rotated session`() = testApplication {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val sessionId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val original = RefreshTokens.create(sessionId)
        val profile = UserSelf(userId.toString(), "Аня", null, "+79991234567", 24, null, now.toString())
        val service = RefreshService(RefreshRepository { _, presented, _, _ ->
            if (presented == RefreshTokens.hash(original))
                RefreshRotationResult.Rotated(profile, userId, 0, now.plusSeconds(3600))
            else RefreshRotationResult.Invalid
        }, TokenIssuer { _, _, _, _, _ -> "new-access" }, Clock.fixed(now, ZoneOffset.UTC))
        application {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady() = true
                override fun close() = Unit
            }, refreshService = service)
        }
        val response = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$original"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.bodyAsText().contains("\"accessToken\":\"new-access\""))
        assertTrue(response.bodyAsText().contains("\"refreshToken\":"))

        val malformed = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"invalid"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, malformed.status)
        assertTrue(malformed.bodyAsText().contains("SESSION_EXPIRED"))
    }
}
