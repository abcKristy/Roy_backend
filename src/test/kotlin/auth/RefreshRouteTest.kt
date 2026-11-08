package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.header
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
        var rotations = 0
        val service = RefreshService(RefreshRepository { _, presented, _, _ ->
            rotations++
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
        for (accept in listOf("application/xml", "application/json;q=0, */*;q=1")) {
            val rejected = client.post("/api/v1/auth/refresh") {
                header(HttpHeaders.Accept, accept)
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$original"}""")
            }
            assertEquals(HttpStatusCode.NotAcceptable, rejected.status)
            assertEquals("", rejected.bodyAsText())
            assertEquals(0, rotations)
        }
        for (mediaType in listOf(ContentType.Text.Plain, ContentType.parse("application/json; charset=iso-8859-1"))) {
            val rejected = client.post("/api/v1/auth/refresh") {
                contentType(mediaType)
                setBody("""{"refreshToken":"$original"}""")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, rejected.status)
            assertTrue(rejected.bodyAsText().contains("UNSUPPORTED_MEDIA_TYPE"))
            assertEquals(0, rotations)
        }
        val response = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$original"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, rotations)
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
