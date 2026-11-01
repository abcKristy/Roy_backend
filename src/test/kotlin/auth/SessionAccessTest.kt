package com.example.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionAccessTest {
    private val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val secret = "test-only-secret-at-least-thirty-two-bytes-long"
    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val user = UserSelf(userId.toString(), "Аня", null, "+79991234567", 24, null, now.toString())

    @Test
    fun `valid token reads fresh session and logout revokes only its id`() = runBlocking {
        val otherSession = UUID.randomUUID()
        val active = mutableSetOf(sessionId, otherSession)
        val repository = object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                user.takeIf { identity.userId == userId && identity.authVersion == 3 && identity.sessionId in active }

            override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean =
                identity.userId == userId && identity.authVersion == 3 && active.remove(identity.sessionId)
        }
        val service = SessionAccessService(secret, repository, clock)
        val token = bearer(sessionId, authVersion = 3)
        assertEquals(user, service.currentUser(token))
        service.logout(token)
        assertEquals(setOf(otherSession), active)
        assertEquals("SESSION_EXPIRED", assertFailsWith<ApiException> { service.currentUser(token) }.code)
        assertEquals("SESSION_EXPIRED", assertFailsWith<ApiException> { service.logout(token) }.code)
        assertEquals(user, service.currentUser(bearer(otherSession, authVersion = 3)))
    }

    @Test
    fun `missing forged expired malformed and mismatched tokens are rejected`() = runBlocking {
        val repository = object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? = null
            override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean = false
        }
        val service = SessionAccessService(secret, repository, clock)
        val missing = assertFailsWith<ApiException> { service.currentUser(null) }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("AUTH_REQUIRED", missing.code)
        val invalid = listOf(
            "Bearer invalid",
            bearer(sessionId, signingSecret = "different-test-secret-at-least-thirty-two-bytes"),
            bearer(sessionId, expiresAt = now.minusSeconds(1)),
            bearer(sessionId, issuedAt = now.plusSeconds(60)),
            bearer(sessionId, authVersion = 8),
        )
        invalid.forEach { authorization ->
            val failure = assertFailsWith<ApiException> { service.currentUser(authorization) }
            assertEquals(HttpStatusCode.Unauthorized, failure.status)
            assertEquals("SESSION_EXPIRED", failure.code)
        }
    }

    @Test
    fun `jwt verification uses injected clock`() = runBlocking {
        val future = Instant.parse("2035-01-01T12:00:00Z")
        val futureClock = Clock.fixed(future, ZoneOffset.UTC)
        val repository = object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                user.takeIf { now == future && identity.sessionId == sessionId }
            override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean = false
        }
        val service = SessionAccessService(secret, repository, futureClock)
        assertEquals(user, service.currentUser(bearer(
            sessionId, issuedAt = future.minusSeconds(10), expiresAt = future.plusSeconds(3600),
        )))
        assertEquals("SESSION_EXPIRED", assertFailsWith<ApiException> {
            service.currentUser(bearer(sessionId, issuedAt = future.minusSeconds(3600), expiresAt = future.minusSeconds(1)))
        }.code)
    }

    private fun bearer(
        sid: UUID,
        authVersion: Int = 0,
        issuedAt: Instant = now.minusSeconds(10),
        expiresAt: Instant = now.plusSeconds(3600),
        signingSecret: String = secret,
    ): String = "Bearer " + JWT.create()
        .withSubject(userId.toString())
        .withJWTId(sid.toString())
        .withClaim("authVersion", authVersion)
        .withIssuedAt(Date.from(issuedAt))
        .withExpiresAt(Date.from(expiresAt))
        .sign(Algorithm.HMAC256(signingSecret))
}
