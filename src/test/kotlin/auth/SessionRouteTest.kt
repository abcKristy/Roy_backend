package com.example.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionRouteTest {
    private val secret = "test-only-secret-at-least-thirty-two-bytes-long"
    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()

    @Test
    fun `me requires bearer and logout revokes current session`() = testApplication {
        var active = true
        val service = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                if (active && identity.userId == userId && identity.sessionId == sessionId && identity.authVersion == 0)
                    UserSelf(userId.toString(), "Аня", null, "+79991234567", 24, null, "2026-11-01T12:00:00Z")
                else null

            override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean {
                if (!active || identity.sessionId != sessionId) return false
                active = false
                return true
            }
        })
        application {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady() = true
                override fun close() = Unit
            }, sessionAccess = service)
        }
        val unauthorized = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertTrue(unauthorized.bodyAsText().contains("AUTH_REQUIRED"))
        val authorization = token()
        val me = client.get("/api/v1/me") { header(HttpHeaders.Authorization, authorization) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"phone\":\"+79991234567\""))
        val logout = client.post("/api/v1/auth/logout") { header(HttpHeaders.Authorization, authorization) }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, authorization)
        }.status)
    }

    private fun token(): String {
        val now = Instant.now()
        return "Bearer " + JWT.create()
            .withSubject(userId.toString())
            .withJWTId(sessionId.toString())
            .withClaim("authVersion", 0)
            .withIssuedAt(Date.from(now.minusSeconds(10)))
            .withExpiresAt(Date.from(now.plusSeconds(3600)))
            .sign(Algorithm.HMAC256(secret))
    }
}
