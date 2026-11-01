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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegistrationRouteTest {
    @Test
    fun `unpublished policy keeps actual route unavailable`() = testApplication {
        var writes = 0
        val service = RegistrationService(
            UnpublishedPrivacyPolicy,
            PasswordHashingWork(Argon2idPasswordHasher()),
            RegistrationRepository { writes++ },
            JwtTokenIssuer("test-only-secret-at-least-thirty-two-bytes-long"),
        )
        application {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady() = true
                override fun close() = Unit
            }, service)
        }

        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"+79991234567","password":"safe-password-123","displayName":"Аня","ageYears":24,"acceptedPrivacyPolicyVersion":"v1"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.headers.contains("X-Request-Id"))
        assertEquals(0, writes)
    }

    @Test
    fun `test publication fixture returns complete session`() = testApplication {
        var writes = 0
        val service = RegistrationService(
            ActivePrivacyPolicy("test-v1"),
            PasswordHashingWork(object : PasswordHasher {
                override fun hash(password: String) = "test-hash"
                override fun verify(password: String, encodedHash: String) = false
                override fun needsRehash(encodedHash: String) = false
                override fun verifyUnknown(password: String) = Unit
            }),
            RegistrationRepository { writes++ },
            JwtTokenIssuer("test-only-secret-at-least-thirty-two-bytes-long"),
        )
        application {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady() = true
                override fun close() = Unit
            }, service)
        }

        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"+79991234567","password":"safe-password-123","displayName":"Аня","ageYears":24,"acceptedPrivacyPolicyVersion":"test-v1"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(1, writes)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"accessToken\""))
        assertTrue(body.contains("\"expiresAt\""))
        assertTrue(body.contains("\"messageMutedUntil\":null"))
        assertTrue(body.contains("\"phone\":\"+79991234567\""))
    }
}
