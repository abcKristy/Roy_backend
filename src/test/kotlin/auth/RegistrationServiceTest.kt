package com.example.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RegistrationServiceTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val secret = "test-only-secret-at-least-thirty-two-bytes-long"
    private val request = RegisterRequest(" +79991234567 ", "safe-password-123", " Аня ", 24, "v1")

    @Test
    fun `unpublished policy rejects before hashing or database write`() = runBlocking {
        var used = false
        val service = service(UnpublishedPrivacyPolicy, repository = { used = true }, onHash = { used = true })

        val failure = assertFailsWith<ApiException> { service.register(request) }
        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
        assertEquals("SERVICE_UNAVAILABLE", failure.code)
        assertEquals(false, used)
    }

    @Test
    fun `stale consent rejects before hashing or database write`() = runBlocking {
        var used = false
        val service = service(ActivePrivacyPolicy("v2"), repository = { used = true }, onHash = { used = true })

        val failure = assertFailsWith<ApiException> { service.register(request) }
        assertEquals(HttpStatusCode.UnprocessableEntity, failure.status)
        assertEquals("PRIVACY_POLICY_ACCEPTANCE_REQUIRED", failure.code)
        assertEquals("v2", failure.details?.get("currentVersion")?.toString()?.trim('"'))
        assertEquals(false, used)
    }

    @Test
    fun `registration writes complete data and issues matching token`() = runBlocking {
        var written: NewRegistration? = null
        val service = service(ActivePrivacyPolicy("v1"), repository = { written = it })

        val session = service.register(request)
        val stored = assertNotNull(written)
        assertEquals("+79991234567", stored.phone)
        assertEquals("Аня", stored.displayName)
        assertEquals("hashed", stored.passwordHash)
        assertEquals("v1", stored.privacyVersion)
        assertEquals(now, stored.createdAt)
        assertEquals(now.plusSeconds(30L * 24 * 60 * 60), stored.expiresAt)
        assertEquals(stored.userId.toString(), session.user.id)
        assertEquals(stored.phone, session.user.phone)
        assertEquals(now.toString(), session.user.createdAt)
        assertEquals(null, session.user.avatarUrl)
        assertEquals(null, session.user.messageMutedUntil)
        val token = JWT.require(Algorithm.HMAC256(secret)).build().verify(session.accessToken)
        assertEquals(stored.userId.toString(), token.subject)
        assertEquals(stored.sessionId.toString(), token.id)
        assertEquals(0, token.getClaim("authVersion").asInt())
        assertEquals(now.epochSecond, token.issuedAt.toInstant().epochSecond)
        assertEquals(stored.expiresAt.epochSecond, token.expiresAt.toInstant().epochSecond)
    }

    @Test
    fun `duplicate phone maps to conflict`() = runBlocking {
        val service = service(ActivePrivacyPolicy("v1"), repository = { throw DuplicatePhoneException() })
        val failure = assertFailsWith<ApiException> { service.register(request) }
        assertEquals(HttpStatusCode.Conflict, failure.status)
        assertEquals("DUPLICATE_PHONE", failure.code)
    }

    @Test
    fun `invalid fields do not write`() = runBlocking {
        var writes = 0
        val service = service(ActivePrivacyPolicy("v1"), repository = { writes++ })
        for (invalid in listOf(
            request.copy(phone = "79991234567"),
            request.copy(password = "short"),
            request.copy(displayName = " "),
            request.copy(ageYears = 11),
        )) {
            val failure = assertFailsWith<ApiException> { service.register(invalid) }
            assertEquals("INVALID_FIELD", failure.code)
        }
        assertEquals(0, writes)
    }

    @Test
    fun `sensitive models do not print credentials or personal data`() {
        val sensitiveRequest = request.toString()
        assertEquals(false, sensitiveRequest.contains(request.password))
        assertEquals(false, sensitiveRequest.contains("79991234567"))
        val user = UserSelf("id", "Аня", phone = "+79991234567", ageYears = 24, createdAt = now.toString())
        val session = Session("secret-jwt", now.toString(), user)
        assertEquals(false, user.toString().contains(user.phone))
        assertEquals(false, session.toString().contains(session.accessToken))
        val registration = NewRegistration(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), user.phone, "secret-hash", user.displayName,
            user.ageYears, "v1", now, now.plusSeconds(60),
        )
        assertEquals(false, registration.toString().contains(user.phone))
        assertEquals(false, registration.toString().contains("secret-hash"))
    }

    private fun service(
        policy: PublishedPrivacyPolicy,
        repository: suspend (NewRegistration) -> Unit,
        onHash: () -> Unit = {},
    ) = RegistrationService(
        policy = policy,
        passwordHashing = PasswordHashingWork(object : PasswordHasher {
            override fun hash(password: String): String {
                onHash()
                return "hashed"
            }
            override fun verify(password: String, encodedHash: String) = false
            override fun needsRehash(encodedHash: String) = false
            override fun verifyUnknown(password: String) = Unit
        }),
        repository = RegistrationRepository { repository(it) },
        tokenIssuer = JwtTokenIssuer(secret),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )
}
