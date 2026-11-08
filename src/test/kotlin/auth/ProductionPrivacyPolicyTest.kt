package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.config.ServerConfig
import com.example.loadPrivacyPolicy
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionPrivacyPolicyTest {
    @Test
    fun `production publishes one document and version for metadata and registration`() {
        val file = Files.createTempFile("roy-policy-test-", ".md")
        try {
            val document = "# Тестовая фикстура\n\nТекст для проверки публикации UTF-8.\n"
            Files.writeString(file, document)
            val policy = requireNotNull(config(file).loadPrivacyPolicy())
            Files.writeString(file, "Changed after startup")
            var registration: NewRegistration? = null
            val service = RegistrationService(
                policy,
                PasswordHashingWork(object : PasswordHasher {
                    override fun hash(password: String) = "test-hash"
                    override fun verify(password: String, encodedHash: String) = false
                    override fun needsRehash(encodedHash: String) = false
                    override fun verifyUnknown(password: String) = Unit
                }),
                RegistrationRepository { registration = it },
                TokenIssuer { _, _, _, _, _ -> "test-token" },
            )
            testApplication {
                application {
                    configureTransport()
                    configureRouting(database, registrationService = service, privacyPolicy = policy)
                }

                val metadataResponse = client.get("/api/v1/legal/privacy")
                assertEquals(HttpStatusCode.OK, metadataResponse.status)
                val metadata = Json.parseToJsonElement(metadataResponse.bodyAsText()).jsonObject
                val version = metadata.getValue("version").jsonPrimitive.content
                assertEquals(VERSION, version)
                assertEquals(PUBLISHED_AT, metadata.getValue("publishedAt").jsonPrimitive.content)
                val url = URI(metadata.getValue("url").jsonPrimitive.content)
                assertEquals("https://policy.example.test/api/v1/legal/privacy/document", url.toString())

                val documentResponse = client.get(url.path)
                assertEquals(HttpStatusCode.OK, documentResponse.status)
                assertTrue(documentResponse.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/markdown"))
                assertEquals(document, documentResponse.bodyAsText())

                val stale = client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(request(DraftPrivacyPolicy.VERSION))
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, stale.status)
                val error = Json.parseToJsonElement(stale.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("PRIVACY_POLICY_ACCEPTANCE_REQUIRED", error.getValue("code").jsonPrimitive.content)
                assertEquals(version, error.getValue("details").jsonObject.getValue("currentVersion").jsonPrimitive.content)
                assertNull(registration)

                val accepted = client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(request(version))
                }
                assertEquals(HttpStatusCode.Created, accepted.status)
                assertEquals(version, registration?.privacyVersion)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `production refuses missing empty invalid UTF-8 and draft documents and future publications`() {
        val file = Files.createTempFile("roy-policy-test-", ".md")
        try {
            fun assertInvalid(publication: ServerConfig = config(file)) {
                val failure = assertFailsWith<IllegalArgumentException> { publication.loadPrivacyPolicy() }
                assertFalse(failure.stackTraceToString().contains(file.toString()))
            }
            assertInvalid(config(file.parent))
            assertInvalid()
            Files.write(file, byteArrayOf(0xff.toByte()))
            assertInvalid()
            Files.writeString(file, DraftPrivacyPolicy(URI("http://127.0.0.1:8080")).document)
            assertInvalid()
            Files.writeString(file, "# Test fixture\n")
            assertInvalid(config(file, Instant.now().plusSeconds(3600).toString()))
            Files.delete(file)
            assertInvalid()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private val database = object : DatabaseRuntime {
        override suspend fun isReady() = true
        override fun close() = Unit
    }

    private fun config(file: Path, publishedAt: String = PUBLISHED_AT) = ServerConfig.fromEnvironment(mapOf(
        "DATABASE_URL" to "jdbc:postgresql://127.0.0.1:5432/roy",
        "DATABASE_USER" to "test",
        "DATABASE_PASSWORD" to "test",
        "JWT_SECRET" to "test-only-secret-at-least-thirty-two-bytes-long",
        "APP_ENV" to "production",
        "PUBLIC_BASE_URL" to "https://policy.example.test/",
        "AUTH_PRIVACY_POLICY_FILE" to file.toString(),
        "AUTH_PRIVACY_POLICY_VERSION" to VERSION,
        "AUTH_PRIVACY_POLICY_PUBLISHED_AT" to publishedAt,
    ))

    private fun request(version: String) =
        """{"phone":"+79990000000","password":"test-password-only","displayName":"Test","ageYears":24,"acceptedPrivacyPolicyVersion":"$version"}"""

    private companion object {
        const val VERSION = "fixture-1"
        const val PUBLISHED_AT = "2020-01-01T00:00:00Z"
    }
}
