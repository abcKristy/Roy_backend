package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.persistence.DatabaseRuntime
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicAuthAdmissionRoutesTest {
    @Test
    fun `registration bounds attempts across normalized phones before hashing or writing`() = testApplication {
        var hashes = 0
        var writes = 0
        installAuth(onRegistrationHash = { hashes++ }, repository = RegistrationRepository { writes++ })

        repeat(3) { assertEquals(HttpStatusCode.Created, client.register(" +79991234567 ").status) }
        client.register("+79991234567").assertLimited("REGISTRATION_RATE_LIMITED")
        repeat(9) { index ->
            assertEquals(HttpStatusCode.Created, client.register("+7999000000$index").status)
        }
        client.register("+79990000009").assertLimited("REGISTRATION_RATE_LIMITED")
        assertEquals(12, hashes)
        assertEquals(12, writes)
    }

    @Test
    fun `registration and login share one fail-fast slot after registration validation`() = testApplication {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        installAuth(onRegistrationHash = {
            if (started.complete(Unit)) check(release.await(10, TimeUnit.SECONDS))
        })

        coroutineScope {
            val inFlight = async { client.register("+79991234567") }
            try {
                withTimeout(5_000) {
                    started.await()
                    client.register("+79990000000").assertLimited("REGISTRATION_RATE_LIMITED", 1)
                    client.login().assertLimited("LOGIN_RATE_LIMITED", 1)
                    val stale = client.register("+79990000000", "old")
                    assertEquals(HttpStatusCode.UnprocessableEntity, stale.status)
                    assertTrue(stale.bodyAsText().contains("PRIVACY_POLICY_ACCEPTANCE_REQUIRED"))
                    val invalid = client.register("invalid")
                    assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
                    assertTrue(invalid.bodyAsText().contains("INVALID_FIELD"))
                }
            } finally {
                release.countDown()
            }
            assertEquals(HttpStatusCode.Created, inFlight.await().status)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.login().status)
    }

    private fun ApplicationTestBuilder.installAuth(
        onRegistrationHash: () -> Unit,
        repository: RegistrationRepository = RegistrationRepository {},
    ) {
        val hashing = PasswordHashingWork(object : PasswordHasher {
            override fun hash(password: String): String {
                onRegistrationHash()
                return "test-hash"
            }
            override fun verify(password: String, encodedHash: String) = false
            override fun needsRehash(encodedHash: String) = false
            override fun verifyUnknown(password: String) = Unit
        })
        val issuer = TokenIssuer { _, _, _, _, _ -> "test-token" }
        val loginRepository = object : LoginRepository {
            override suspend fun findCredential(phone: String): LoginCredential? = null
            override suspend fun createSession(session: NewLoginSession): UserSelf? = null
        }
        application {
            configureTransport()
            configureRouting(
                database = object : DatabaseRuntime {
                    override suspend fun isReady() = true
                    override fun close() = Unit
                },
                registrationService = RegistrationService(ActivePrivacyPolicy("v1"), hashing, repository, issuer),
                loginService = LoginService(loginRepository, hashing, issuer),
            )
        }
    }

    private suspend fun HttpClient.register(phone: String, policyVersion: String = "v1") = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"phone":"$phone","password":"test-password-only","displayName":"Test","ageYears":24,"acceptedPrivacyPolicyVersion":"$policyVersion"}""")
    }

    private suspend fun HttpClient.login() = post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"phone":"+79990000000","password":"test-password-only"}""")
    }

    private suspend fun HttpResponse.assertLimited(code: String, expectedRetry: Int? = null) {
        assertEquals(HttpStatusCode.TooManyRequests, status)
        val retry = checkNotNull(headers[HttpHeaders.RetryAfter]).toInt()
        assertTrue(retry in 1..60)
        expectedRetry?.let { assertEquals(it, retry) }
        val error = Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals(code, error.getValue("code").jsonPrimitive.content)
        assertEquals(retry, error.getValue("retryAfterSeconds").jsonPrimitive.int)
    }
}
