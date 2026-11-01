package com.example.auth

import com.example.api.ApiException
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class RefreshServiceTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val user = UserSelf(userId.toString(), "Аня", null, "+79991234567", 24, null, now.toString())

    @Test
    fun `rotation issues new access and refresh within fixed session expiry`() = runBlocking {
        val original = RefreshTokens.create(sessionId)
        var currentHash = RefreshTokens.hash(original)
        val expiry = now.plusSeconds(100)
        val repository = RefreshRepository { id, presented, replacement, at ->
            assertEquals(sessionId, id)
            assertEquals(now, at)
            if (presented != currentHash) RefreshRotationResult.Invalid else {
                currentHash = replacement
                RefreshRotationResult.Rotated(user, userId, 7, expiry)
            }
        }
        var issuedExpiry: Instant? = null
        val service = RefreshService(repository, TokenIssuer { _, _, version, _, expires ->
            assertEquals(7, version)
            issuedExpiry = expires
            "access"
        }, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(15))

        val result = service.refresh(RefreshRequest(original))
        assertEquals("access", result.accessToken)
        assertEquals(expiry, issuedExpiry)
        assertEquals(expiry.toString(), result.expiresAt)
        assertEquals(expiry.toString(), result.refreshExpiresAt)
        assertNotEquals(original, result.refreshToken)
        assertEquals(currentHash, RefreshTokens.hash(result.refreshToken))
        assertEquals(user, result.user)
    }

    @Test
    fun `malformed token fails before repository and invalid rotation has one error`() = runBlocking {
        var calls = 0
        val service = RefreshService(RefreshRepository { _, _, _, _ ->
            calls++
            RefreshRotationResult.Invalid
        }, TokenIssuer { _, _, _, _, _ -> "access" }, Clock.fixed(now, ZoneOffset.UTC))
        for (token in listOf("", "invalid", "$sessionId.short", "$sessionId.${"a".repeat(43)} ")) {
            val failure = assertFailsWith<ApiException> { service.refresh(RefreshRequest(token)) }
            assertEquals("SESSION_EXPIRED", failure.code)
        }
        assertEquals(0, calls)
        val valid = RefreshTokens.create(sessionId)
        val failure = assertFailsWith<ApiException> { service.refresh(RefreshRequest(valid)) }
        assertEquals("SESSION_EXPIRED", failure.code)
        assertEquals(1, calls)
    }

    @Test
    fun `replay result never issues token`() = runBlocking {
        var issued = false
        val service = RefreshService(RefreshRepository { _, _, _, _ -> RefreshRotationResult.Replayed },
            TokenIssuer { _, _, _, _, _ -> issued = true; "access" }, Clock.fixed(now, ZoneOffset.UTC))
        val failure = assertFailsWith<ApiException> { service.refresh(RefreshRequest(RefreshTokens.create(sessionId))) }
        assertEquals("SESSION_EXPIRED", failure.code)
        assertEquals(false, issued)
    }

    @Test
    fun `refresh request does not print token`() {
        val request = RefreshRequest(RefreshTokens.create(sessionId))
        assertEquals(false, request.toString().contains(request.refreshToken))
    }
}
