package com.example.api

import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.PasswordHashingWork
import com.example.auth.ProfileService
import com.example.auth.SessionAccessService
import com.example.auth.UserSelf
import com.example.config.ServerConfig
import com.example.persistence.DatabaseRuntime
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.testing.testDatabaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfileNameRoutesIntegrationTest {
    @Test
    fun `patch changes only the current account name and returns canonical UserSelf`() = profileTest { client, runtime, user, other ->
        val before = runtime.snapshot(user.id)
        val otherBefore = runtime.snapshot(other.id)
        val response = client.patchName(user, nameBody("  Новое имя  "))

        val expected = Json.parseToJsonElement(
            """{"id":"${user.id}","displayName":"Новое имя","avatarUrl":null,"phone":"${user.phone}",
               "ageYears":24,"messageMutedUntil":null,"createdAt":"2026-11-01T12:00:00Z"}"""
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(expected, Json.parseToJsonElement(response.bodyAsText()))
        val me = client.getMe(user)
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(expected, Json.parseToJsonElement(me.bodyAsText()))
        val after = runtime.snapshot(user.id)
        assertNotEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.copy(name = "Новое имя", updatedAt = after.updatedAt), after)
        assertEquals(otherBefore, runtime.snapshot(other.id))
    }

    @Test
    fun `repeating the trimmed name leaves the database row untouched`() = profileTest { client, runtime, user, _ ->
        val first = client.patchName(user, nameBody("Новое имя"))
        assertEquals(HttpStatusCode.OK, first.status)
        val afterFirst = runtime.snapshot(user.id)

        val repeat = client.patchName(user, nameBody("  Новое имя  "))
        assertEquals(HttpStatusCode.OK, repeat.status)
        assertEquals(first.userSelf(), repeat.userSelf())
        assertEquals(afterFirst, runtime.snapshot(user.id))
    }

    @Test
    fun `patch enforces trimmed Unicode code point boundaries`() = profileTest { client, runtime, user, _ ->
        val symbol = "🌱"
        for (name in listOf(symbol, symbol.repeat(80))) {
            val response = client.patchName(user, nameBody("  $name  "))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(name, response.userSelf().displayName)
        }
        val escapedPair = client.patchName(user, """{"displayName":"\ud83c\udf31"}""")
        assertEquals(HttpStatusCode.OK, escapedPair.status)
        assertEquals(symbol, escapedPair.userSelf().displayName)
        val before = runtime.snapshot(user.id)
        for (name in listOf("", "   ", symbol.repeat(81))) {
            client.patchName(user, nameBody(name)).assertError(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD")
        }
        assertEquals(before, runtime.snapshot(user.id))
    }

    @Test
    fun `patch rejects escaped NUL`() = rejectsInvalidCodePoint("""{"displayName":"A\u0000B"}""")

    @Test
    fun `patch rejects an escaped unpaired high surrogate`() =
        rejectsInvalidCodePoint("""{"displayName":"A\ud800B"}""")

    @Test
    fun `patch rejects an escaped unpaired low surrogate`() =
        rejectsInvalidCodePoint("""{"displayName":"A\udfffB"}""")

    private fun rejectsInvalidCodePoint(body: String) = profileTest { client, runtime, user, _ ->
        val before = runtime.snapshot(user.id)
        val response = client.patchName(user, body)
        assertEquals(before, runtime.snapshot(user.id), "Invalid name must preserve the saved profile (HTTP ${response.status.value})")
        response.assertError(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD")
    }

    @Test
    fun `patch rejects fields outside the name contract without mutating the profile`() =
        profileTest { client, runtime, user, _ ->
            val before = runtime.snapshot(user.id)
            val invalidBodies = listOf(
                """{"displayName":"Новое имя","phone":"+79991234567"}""",
                """{"displayName":"Новое имя","ageYears":25}""",
                """{"displayName":"Новое имя","unexpected":true}""",
                """{"displayName":null}""",
                """{}""",
            )
            for (body in invalidBodies) {
                client.patchName(user, body).assertError(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD")
            }
            client.patchName(user, """{"displayName":""")
                .assertError(HttpStatusCode.BadRequest, "INVALID_JSON")
            assertEquals(before, runtime.snapshot(user.id))
        }

    @Test
    fun `patch requires an active session even when the requested name already matches`() =
        profileTest { client, runtime, user, _ ->
            val before = runtime.snapshot(user.id)
            client.patchName(null, nameBody("Новое имя")).assertError(HttpStatusCode.Unauthorized, "AUTH_REQUIRED")
            val logout = client.post("/api/v1/auth/logout") {
                header(HttpHeaders.Authorization, user.authorization)
            }
            assertEquals(HttpStatusCode.NoContent, logout.status)
            client.patchName(user, nameBody(before.name)).assertError(HttpStatusCode.Unauthorized, "SESSION_EXPIRED")
            assertEquals(before, runtime.snapshot(user.id))
        }

    @Test
    fun `delivering the earlier patch response after a newer commit preserves the newer name`() {
        val responseReady = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        profileTest(
            beforeRespond = { user ->
                if (user.displayName == "Первое имя") {
                    responseReady.complete(Unit)
                    releaseResponse.await()
                }
            },
        ) { client, runtime, user, _ ->
            withTimeout(10_000) {
                coroutineScope {
                    val earlier = async { client.patchName(user, nameBody("Первое имя")) }
                    try {
                        responseReady.await()
                        assertEquals("Первое имя", runtime.snapshot(user.id).name)
                        val later = client.patchName(user, nameBody("Второе имя"))
                        assertEquals(HttpStatusCode.OK, later.status)
                        assertEquals("Второе имя", later.userSelf().displayName)
                        assertEquals("Второе имя", runtime.snapshot(user.id).name)
                    } finally {
                        releaseResponse.complete(Unit)
                    }
                    val delayed = earlier.await()
                    assertEquals(HttpStatusCode.OK, delayed.status)
                    assertEquals("Первое имя", delayed.userSelf().displayName)
                }
            }
            assertEquals("Второе имя", runtime.snapshot(user.id).name)
            val me = client.getMe(user)
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals("Второе имя", me.userSelf().displayName)
        }
    }

    private fun profileTest(
        beforeRespond: suspend (UserSelf) -> Unit = {},
        test: suspend (HttpClient, DatabaseRuntime, FixtureUser, FixtureUser) -> Unit,
    ) = testApplication {
        val url = testDatabaseUrl()
        require(url.endsWith("/roy_m5t115_profile"))
        val secret = "test-only-secret-at-least-thirty-two-bytes-long"
        val config = ServerConfig(
            url,
            System.getenv("DATABASE_USER") ?: error("DATABASE_USER required"),
            System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required"),
            secret,
        )
        HikariDatabaseBootstrap(config).start().use { runtime ->
            val user = runtime.seedUser(secret)
            val other = runtime.seedUser(secret)
            application {
                sendPipeline.intercept(ApplicationSendPipeline.Before) {
                    (subject as? UserSelf)?.let { beforeRespond(it) }
                }
                configureTransport()
                configureRouting(
                    runtime,
                    sessionAccess = SessionAccessService(secret, JdbcSessionRepository(runtime)),
                    profileService = ProfileService(runtime, PasswordHashingWork(Argon2idPasswordHasher())),
                )
            }
            test(client, runtime, user, other)
        }
    }

    private suspend fun DatabaseRuntime.seedUser(secret: String): FixtureUser {
        val id = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        withJdbcTransaction { connection ->
            connection.prepareStatement(
                """INSERT INTO users (id, phone_e164, password_hash, display_name, age_years,
                   accepted_privacy_version, accepted_privacy_at, created_at, updated_at)
                   VALUES (?, ?, 'test-hash', 'Аня', 24, 'test', clock_timestamp(),
                   TIMESTAMPTZ '2026-11-01 12:00:00+00', TIMESTAMPTZ '2026-11-01 12:00:00+00')"""
            ).use {
                it.setObject(1, id)
                it.setString(2, phone)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO auth_sessions (id, user_id, created_at, expires_at, auth_version, refresh_token_hash)
                   VALUES (?, ?, clock_timestamp(), clock_timestamp() + INTERVAL '1 hour', 0, ?)"""
            ).use {
                it.setObject(1, sessionId)
                it.setObject(2, id)
                it.setString(3, sessionId.toString().replace("-", "").repeat(2))
                it.executeUpdate()
            }
        }
        val now = Instant.now()
        val token = JwtTokenIssuer(secret).issue(id, sessionId, 0, now.minusSeconds(1), now.plusSeconds(3600))
        return FixtureUser(id, phone, "Bearer $token")
    }

    private suspend fun DatabaseRuntime.snapshot(id: UUID): ProfileSnapshot = withJdbcTransaction { connection ->
        connection.prepareStatement(
            "SELECT display_name, phone_e164, age_years, updated_at FROM users WHERE id = ?"
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                ProfileSnapshot(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getTimestamp(4).toInstant())
            }
        }
    }

    private suspend fun HttpClient.patchName(user: FixtureUser?, body: String) = patch("/api/v1/me") {
        if (user != null) header(HttpHeaders.Authorization, user.authorization)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.getMe(user: FixtureUser) = get("/api/v1/me") {
        header(HttpHeaders.Authorization, user.authorization)
    }

    private fun nameBody(name: String) = JsonObject(mapOf("displayName" to JsonPrimitive(name))).toString()

    private suspend fun HttpResponse.userSelf(): UserSelf = Json.decodeFromString(bodyAsText())

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status)
        val body = Json.parseToJsonElement(bodyAsText()).jsonObject
        assertEquals(code, body.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    private data class FixtureUser(val id: UUID, val phone: String, val authorization: String)
    private data class ProfileSnapshot(val name: String, val phone: String, val age: Int, val updatedAt: Instant)
}
