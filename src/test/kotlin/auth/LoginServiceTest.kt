package com.example.auth

import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LoginServiceTest {
    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val profile = UserSelf(userId.toString(), "Аня", null, "+79991234567", 24, null, now.toString())

    @Test
    fun `unknown phone and wrong password have identical public error`() = runBlocking {
        val hasher = FakeHasher()
        val repository = FakeRepository(LoginCredential(userId, "stored-hash", 0), profile)
        val service = service(repository, hasher)

        val unknown = assertFailsWith<ApiException> {
            service.login(LoginRequest("+79990000000", "wrong-password"))
        }
        val wrong = assertFailsWith<ApiException> {
            service.login(LoginRequest("+79991234567", "wrong-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(unknown.status, wrong.status)
        assertEquals("INVALID_CREDENTIALS", unknown.code)
        assertEquals(unknown.code, wrong.code)
        assertEquals(unknown.safeMessage, wrong.safeMessage)
        assertEquals(1, hasher.dummyChecks)
        assertEquals(1, hasher.realChecks)
        assertEquals(0, repository.sessions.size)
    }

    @Test
    fun `successful login persists current session and returns complete profile`() = runBlocking {
        val hasher = FakeHasher()
        val repository = FakeRepository(LoginCredential(userId, "stored-hash", 7), profile)
        var issuedVersion = -1
        val service = service(repository, hasher, TokenIssuer { _, _, version, _, _ ->
            issuedVersion = version
            "test-token"
        })

        val result = service.login(LoginRequest(" +79991234567 ", "correct-password"))

        assertEquals("test-token", result.accessToken)
        assertEquals("2026-12-01T12:00:00Z", result.expiresAt)
        assertEquals(profile, result.user)
        assertEquals(7, issuedVersion)
        val saved = repository.sessions.single()
        assertEquals(userId, saved.userId)
        assertEquals(7, saved.expectedAuthVersion)
        assertEquals(now, saved.createdAt)
        assertEquals(now.plusSeconds(30L * 24 * 60 * 60), saved.expiresAt)
        assertEquals(null, saved.replacementPasswordHash)
    }

    @Test
    fun `rehash happens only after successful verification and never before session write`() = runBlocking {
        val hasher = FakeHasher(rehash = true)
        val repository = FakeRepository(LoginCredential(userId, "old-hash", 0), profile)
        val service = service(repository, hasher)

        service.login(LoginRequest("+79991234567", "correct-password"))

        assertEquals(1, hasher.hashes)
        assertEquals("new-hash", repository.sessions.single().replacementPasswordHash)
        assertEquals("old-hash", repository.sessions.single().expectedPasswordHash)
    }

    @Test
    fun `changed credential race does not issue a usable token`() = runBlocking {
        val hasher = FakeHasher()
        val repository = FakeRepository(LoginCredential(userId, "stored-hash", 0), null)
        var issued = false
        val service = service(repository, hasher, TokenIssuer { _, _, _, _, _ ->
            issued = true
            "test-token"
        })

        val failure = assertFailsWith<ApiException> {
            service.login(LoginRequest("+79991234567", "correct-password"))
        }
        assertEquals("INVALID_CREDENTIALS", failure.code)
        assertFalse(issued)
    }

    @Test
    fun `configured session ttl controls expiry`() = runBlocking {
        val repository = FakeRepository(LoginCredential(userId, "stored-hash", 0), profile)
        val service = LoginService(
            repository, PasswordHashingWork(FakeHasher()),
            TokenIssuer { _, _, _, _, _ -> "test-token" },
            Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(1),
        )

        val result = service.login(LoginRequest("+79991234567", "correct-password"))

        assertEquals("2026-11-01T13:00:00Z", result.expiresAt)
        assertEquals(now.plusSeconds(3600), repository.sessions.single().expiresAt)
    }

    private fun service(
        repository: LoginRepository,
        hasher: FakeHasher,
        tokenIssuer: TokenIssuer = TokenIssuer { _, _, _, _, _ -> "test-token" },
    ) = LoginService(repository, PasswordHashingWork(hasher), tokenIssuer, Clock.fixed(now, ZoneOffset.UTC))

    private class FakeHasher(private val rehash: Boolean = false) : PasswordHasher {
        var dummyChecks = 0
        var realChecks = 0
        var hashes = 0
        override fun hash(password: String): String {
            hashes++
            return "new-hash"
        }
        override fun verify(password: String, encodedHash: String): Boolean {
            realChecks++
            return password == "correct-password"
        }
        override fun needsRehash(encodedHash: String) = rehash
        override fun verifyUnknown(password: String) {
            dummyChecks++
        }
    }

    private class FakeRepository(
        private val credential: LoginCredential?,
        private val result: UserSelf?,
    ) : LoginRepository {
        val sessions = mutableListOf<NewLoginSession>()
        override suspend fun findCredential(phone: String): LoginCredential? =
            if (phone == "+79991234567") credential else null
        override suspend fun createSession(session: NewLoginSession): UserSelf? {
            if (result != null) sessions += session
            return result
        }
    }
}
