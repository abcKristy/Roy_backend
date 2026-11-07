package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.notifications.NotificationService
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationSettingsIntegrationTest {
    @Test
    fun concurrentUpdatesReturnWinnerToStaleWriter() = withSettings { fixture ->
        assertEquals(defaultSettings, fixture.get())
        val candidates = listOf(
            settings(false, true, true, false, 1),
            settings(true, false, false, true, 1),
        )
        val replies = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val writes = candidates.map { candidate ->
                async {
                    start.await()
                    fixture.put(candidate.updateBody(expectedRevision = 0))
                }
            }
            start.complete(Unit)
            writes.awaitAll()
        }
        assertEquals(listOf(200, 409), replies.map { it.status.value }.sorted())
        val winnerIndex = replies.indexOfFirst { it.status == HttpStatusCode.OK }
        val winner = candidates[winnerIndex]
        assertEquals(winner, replies[winnerIndex].json())
        assertEquals(winner, replies[1 - winnerIndex].expectError(HttpStatusCode.Conflict, "STALE_STATE")["details"])
        assertEquals(winner, fixture.get())
    }

    @Test
    fun staleReplayAfterNewerUpdateCannotChangeSettings() = withSettings { fixture ->
        val first = settings(false, false, true, true, 1)
        val firstRequest = first.updateBody(expectedRevision = 0)
        assertEquals(first, fixture.put(firstRequest).expectSettings())

        val latest = settings(true, false, true, false, 2)
        assertEquals(latest, fixture.put(latest.updateBody(expectedRevision = 1)).expectSettings())
        repeat(2) {
            val error = fixture.put(firstRequest).expectError(HttpStatusCode.Conflict, "STALE_STATE")
            assertEquals(latest, error["details"])
        }
        assertEquals(latest, fixture.get())
    }

    @Test
    fun repeatedV006BackfillPreservesChangedSettings() = withSettings(seedSettings = false) { fixture ->
        val migration = checkNotNull(javaClass.getResourceAsStream(
            "/db/migration/V006__existing_data_access_indexes.sql"
        )).bufferedReader().use { it.readText() }
        // Re-execute the idempotent backfill statement as shipped, without replaying V006 DDL.
        val backfill = checkNotNull(Regex("(?s)INSERT INTO notification_settings\\b[^;]+;").find(migration)).value

        assertEquals(1, fixture.execute(backfill))
        assertEquals(defaultSettings, fixture.get())
        val changed = settings(false, false, true, true, 1)
        assertEquals(changed, fixture.put(changed.updateBody(expectedRevision = 0)).expectSettings())
        repeat(2) {
            assertEquals(0, fixture.execute(backfill))
            assertEquals(changed, fixture.get())
        }
    }

    @Test
    fun quotedBooleanIsRejected() = withSettings { fixture ->
        val body = defaultSettings.updateBody(expectedRevision = 0)
            .replace("\"remindersPushEnabled\":true", "\"remindersPushEnabled\":\"true\"")
        fixture.put(body).expectError(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD")
        assertEquals(defaultSettings, fixture.get())
    }

    @Test
    fun quotedRevisionIsRejected() = withSettings { fixture ->
        val body = defaultSettings.updateBody(expectedRevision = 0)
            .replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\"")
        fixture.put(body).expectError(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD")
        assertEquals(defaultSettings, fixture.get())
    }

    @Test
    fun malformedJsonIsRejected() = withSettings { fixture ->
        fixture.put("{").expectError(HttpStatusCode.BadRequest, "INVALID_JSON")
        assertEquals(defaultSettings, fixture.get())
    }

    @Test
    fun unauthorizedRequestsCannotAccessSettings() = withSettings { fixture ->
        val write = settings(false, false, true, true, 1).updateBody(expectedRevision = 0)
        fixture.getWithoutToken().expectError(HttpStatusCode.Unauthorized, "AUTH_REQUIRED")
        fixture.put(write, authorized = false).expectError(HttpStatusCode.Unauthorized, "AUTH_REQUIRED")
        assertEquals(defaultSettings, fixture.get())
    }

    private fun withSettings(seedSettings: Boolean = true, test: suspend (SettingsFixture) -> Unit) {
        val url = testDatabaseUrl()
        require(url.endsWith("/roy_m5t124_settings"))
        val dbUser = checkNotNull(System.getenv("DATABASE_USER"))
        val dbPassword = checkNotNull(System.getenv("DATABASE_PASSWORD"))
        val actor = UUID.randomUUID()
        fun connection() = DriverManager.getConnection(url, dbUser, dbPassword)
        connection().use { connection ->
            val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'fixture-hash','Fixture',20,'fixture',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, phone)
                statement.executeUpdate()
            }
            if (seedSettings) {
                connection.prepareStatement(
                    "INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, actor)
                    statement.executeUpdate()
                }
            }
        }
        val secret = "notification-settings-fixture-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        try {
            val sessionId = UUID.randomUUID()
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == sessionId)
                        UserSelf(actor.toString(), "Fixture", null, "+79990000000", 20, null, now.toString()) else null

                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            val now = Instant.now()
            val token = "Bearer " + JwtTokenIssuer(secret).issue(actor, sessionId, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            testApplication {
                application {
                    configureTransport()
                    routing { notificationRoutes(NotificationService(runtime, secret), sessions) }
                }
                test(SettingsFixture(client, token, ::connection))
            }
        } finally {
            runtime.close()
        }
    }

    private class SettingsFixture(val client: HttpClient, val token: String, val connection: () -> Connection) {
        suspend fun get(): JsonObject = client.get(path) {
            header(HttpHeaders.Authorization, token)
        }.expectSettings()

        suspend fun getWithoutToken(): HttpResponse = client.get(path)

        suspend fun put(body: String, authorized: Boolean = true): HttpResponse = client.put(path) {
            if (authorized) header(HttpHeaders.Authorization, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        fun execute(sql: String): Int = connection().use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }
    }

    private companion object {
        const val path = "/api/v1/me/notification-settings"
        val defaultSettings = settings(true, true, false, false, 0)

        fun settings(reminders: Boolean, personal: Boolean, extraPush: Boolean, extraInApp: Boolean, revision: Long) =
            buildJsonObject {
                put("remindersPushEnabled", reminders)
                put("personalPushEnabled", personal)
                put("extraPushEnabled", extraPush)
                put("extraInAppEnabled", extraInApp)
                put("revision", revision)
            }

        fun JsonObject.updateBody(expectedRevision: Long): String = buildJsonObject {
            for ((key, value) in this@updateBody) {
                if (key != "revision") put(key, value)
            }
            put("expectedRevision", expectedRevision)
        }.toString()

        suspend fun HttpResponse.expectSettings(): JsonObject {
            assertEquals(HttpStatusCode.OK, status)
            return json()
        }

        suspend fun HttpResponse.expectError(expectedStatus: HttpStatusCode, code: String): JsonObject {
            assertEquals(expectedStatus, status)
            val error = json().getValue("error").jsonObject
            assertEquals(code, error.getValue("code").jsonPrimitive.content)
            return error
        }

        suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    }
}
