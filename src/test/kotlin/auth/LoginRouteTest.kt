package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginRouteTest {
    @Test
    fun `login returns session and generic error without auth header`() = testApplication {
        val userId = UUID.randomUUID()
        val profile = UserSelf(userId.toString(), "Аня", null, "+79991234567", 24, null, "2026-11-01T12:00:00Z")
        val repository = object : LoginRepository {
            override suspend fun findCredential(phone: String): LoginCredential? =
                if (phone == "+79991234567") LoginCredential(userId, "stored", 0) else null
            override suspend fun createSession(session: NewLoginSession) = profile
        }
        val hasher = object : PasswordHasher {
            override fun hash(password: String) = "unused"
            override fun verify(password: String, encodedHash: String) = password == "correct-password"
            override fun needsRehash(encodedHash: String) = false
            override fun verifyUnknown(password: String) = Unit
        }
        val service = LoginService(
            repository,
            PasswordHashingWork(hasher),
            TokenIssuer { _, _, _, _, _ -> "test-token" },
            Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC),
        )
        application {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady() = true
                override fun close() = Unit
            }, loginService = service)
        }

        val success = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"+79991234567","password":"correct-password"}""")
        }
        assertEquals(HttpStatusCode.OK, success.status)
        val body = success.bodyAsText()
        assertTrue(body.contains("\"accessToken\":\"test-token\""))
        assertTrue(body.contains("\"phone\":\"+79991234567\""))
        assertTrue(body.contains("\"messageMutedUntil\":null"))

        val wrong = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"+79991234567","password":"wrong-password"}""")
        }
        val unknown = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"+79990000000","password":"wrong-password"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(wrong.status, unknown.status)
        assertTrue(wrong.bodyAsText().contains("INVALID_CREDENTIALS"))
        assertTrue(unknown.bodyAsText().contains("INVALID_CREDENTIALS"))
    }
}
