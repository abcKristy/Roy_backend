package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DraftPrivacyRouteTest {
    private val database = object : DatabaseRuntime {
        override suspend fun isReady() = true
        override fun close() = Unit
    }

    @Test
    fun `metadata and document are public and stable`() = testApplication {
        val first = DraftPrivacyPolicy(URI("http://127.0.0.1:8080"))
        val restarted = DraftPrivacyPolicy(URI("http://127.0.0.1:8080"))
        assertEquals(first.publishedAt, restarted.publishedAt)
        assertEquals(first.version, restarted.version)
        application {
            configureTransport()
            configureRouting(database, draftPrivacyPolicy = first)
        }

        val metadataResponse = client.get("/api/v1/legal/privacy")
        assertEquals(HttpStatusCode.OK, metadataResponse.status)
        val metadata = Json.parseToJsonElement(metadataResponse.bodyAsText()).jsonObject
        assertEquals(DraftPrivacyPolicy.VERSION, metadata.getValue("version").jsonPrimitive.content)
        assertEquals(first.publishedAt.toString(), metadata.getValue("publishedAt").jsonPrimitive.content)
        assertEquals("http://127.0.0.1:8080/api/v1/legal/privacy/document", metadata.getValue("url").jsonPrimitive.content)

        val documentResponse = client.get(DraftPrivacyPolicy.DOCUMENT_PATH)
        assertEquals(HttpStatusCode.OK, documentResponse.status)
        assertTrue(documentResponse.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/markdown"))
        assertTrue(documentResponse.bodyAsText().contains(DraftPrivacyPolicy.VERSION))
        assertEquals(first.document, documentResponse.bodyAsText())
    }

    @Test
    fun `document header and full digest must match immutable metadata`() {
        val origin = URI("http://127.0.0.1:8080")
        val original = DraftPrivacyPolicy(origin).document
        val nextVersion = DraftPrivacyPolicy.VERSION.substringBeforeLast('.') + "." +
            (DraftPrivacyPolicy.VERSION.substringAfterLast('.').toInt() + 1)
        val changedHeader = original.replaceFirst(DraftPrivacyPolicy.VERSION, nextVersion)
        assertTrue(assertFailsWith<IllegalArgumentException> {
            DraftPrivacyPolicy(origin, changedHeader)
        }.message.orEmpty().contains("header version"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            DraftPrivacyPolicy(origin, original + "\nA changed sentence.\n")
        }.message.orEmpty().contains("content changed"))
        DraftPrivacyPolicy(origin, original.replace("\r\n", "\n").replace("\n", "\r\n"))
    }

    @Test
    fun `disabled draft does not publish metadata or document`() = testApplication {
        application {
            configureTransport()
            configureRouting(database)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/legal/privacy").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(DraftPrivacyPolicy.DOCUMENT_PATH).status)
    }

    @Test
    fun `local draft version allows registration and rejects stale consent`() = testApplication {
        val draft = DraftPrivacyPolicy(URI("http://127.0.0.1:8080"))
        var writes = 0
        val service = RegistrationService(
            draft,
            PasswordHashingWork(object : PasswordHasher {
                override fun hash(password: String) = "test-hash"
                override fun verify(password: String, encodedHash: String) = false
                override fun needsRehash(encodedHash: String) = false
                override fun verifyUnknown(password: String) = Unit
            }),
            RegistrationRepository { writes++ },
            TokenIssuer { _, _, _, _, _ -> "test-token" },
        )
        application {
            configureTransport()
            configureRouting(database, registrationService = service, draftPrivacyPolicy = draft)
        }
        val stale = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(request("old-version"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, stale.status)
        assertTrue(stale.bodyAsText().contains("PRIVACY_POLICY_ACCEPTANCE_REQUIRED"))
        assertEquals(0, writes)

        val accepted = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(request(draft.version))
        }
        assertEquals(HttpStatusCode.Created, accepted.status)
        assertEquals(1, writes)
        assertTrue(accepted.bodyAsText().contains("test-token"))
    }

    private fun request(version: String) =
        """{"phone":"+79990000000","password":"test-password-only","displayName":"Test","ageYears":24,"acceptedPrivacyPolicyVersion":"$version"}"""
}
