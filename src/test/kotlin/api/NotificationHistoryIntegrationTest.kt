package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.notifications.NotificationService
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.testing.testDatabaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationHistoryIntegrationTest {
    @Test
    fun `history pages stay stable and private across identical timestamps and new inserts`() = withHistory {
        val sameTime = Instant.parse("2026-11-01T12:00:00Z")
        val ids = List(3) { UUID(owner.mostSignificantBits, it.toLong() + 1) }
        ids.forEach { seedEvent(owner, sameTime, id = it) }
        seedEvent(owner, sameTime.plusSeconds(1), visible = false)
        val otherEvent = seedEvent(other, sameTime)

        val first = list(ownerToken, limit = 2).expectJson(HttpStatusCode.OK)
        assertEquals(listOf(ids[2].toString(), ids[1].toString()), first.ids())
        val cursor = first.getValue("nextCursor").jsonPrimitive.content

        val newEvent = seedEvent(owner, sameTime.plusSeconds(2))
        val next = list(ownerToken, limit = 2, cursor = cursor).expectJson(HttpStatusCode.OK)
        assertEquals(listOf(ids[0].toString()), next.ids())
        assertEquals(JsonNull, next.getValue("nextCursor"))
        assertEquals(next, list(ownerToken, limit = 2, cursor = cursor).expectJson(HttpStatusCode.OK))
        val refreshed = list(ownerToken, limit = 2).expectJson(HttpStatusCode.OK)
        assertEquals(listOf(newEvent.toString(), ids[2].toString()), refreshed.ids())
        refreshed.getValue("items").jsonArray.forEach { assertEquals(JsonNull, it.jsonObject.getValue("readAt")) }

        assertEquals(listOf(otherEvent.toString()), list(otherToken, limit = 2).expectJson(HttpStatusCode.OK).ids())
        list(otherToken, limit = 2, cursor = cursor).expectInvalidCursor()
        list(ownerToken, limit = 3, cursor = cursor).expectInvalidCursor()
        val changedPrefix = if (cursor.first() == 'A') 'B' else 'A'
        val forged = changedPrefix + cursor.drop(1)
        list(ownerToken, limit = 2, cursor = forged).expectInvalidCursor()
        assertEquals("no-store", list(ownerToken, limit = 2).headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `read uses one server timestamp across retries and protects foreign and hidden events`() = withHistory {
        val event = seedEvent(owner, Instant.now().minusSeconds(300))
        val hidden = seedEvent(owner, Instant.now(), visible = false)
        val otherEvent = seedEvent(other, Instant.now())
        val missing = UUID.randomUUID()
        for ((token, id) in listOf(otherToken to event, ownerToken to hidden, ownerToken to otherEvent, ownerToken to missing)) {
            assertEquals("RESOURCE_GONE", markRead(token, id).expectJson(HttpStatusCode.NotFound).errorCode())
        }
        assertNull(readAt(event))
        assertNull(readAt(hidden))
        assertNull(readAt(otherEvent))

        val before = Instant.now().minusSeconds(1)
        val responses = coroutineScope {
            val ready = CompletableDeferred<Unit>()
            val requests = List(3) { async { ready.await(); markRead(ownerToken, event) } }
            ready.complete(Unit)
            requests.awaitAll()
        }
        val snapshots = responses.map { it.expectJson(HttpStatusCode.OK) }
        val canonical = snapshots.first()
        snapshots.forEach { assertEquals(canonical, it) }
        val firstRead = Instant.parse(canonical.getValue("readAt").jsonPrimitive.content)
        assertTrue(firstRead in before..Instant.now().plusSeconds(1))
        assertEquals(firstRead, readAt(event))
        assertEquals(canonical, list(ownerToken, limit = 2).expectJson(HttpStatusCode.OK)
            .getValue("items").jsonArray.single().jsonObject)
        val repeated = markRead(ownerToken, event)
        assertEquals(canonical, repeated.expectJson(HttpStatusCode.OK))
        assertEquals("no-store", repeated.headers[HttpHeaders.CacheControl])
    }

    private fun withHistory(test: suspend HistoryFixture.() -> Unit) = testApplication {
        val url = testDatabaseUrl()
        require(url.endsWith("/roy_m1t2_step7notifications"))
        val dbUser = checkNotNull(System.getenv("DATABASE_USER"))
        val dbPassword = checkNotNull(System.getenv("DATABASE_PASSWORD"))
        val secret = "notification-history-test-secret-at-least-32-bytes"
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        fun connection() = DriverManager.getConnection(url, dbUser, dbPassword)
        val sessionIds = listOf(owner, other).associateWith { UUID.randomUUID() }
        connection().use { connection ->
            connection.autoCommit = false
            sessionIds.forEach { (userId, sessionId) -> seedAccount(connection, userId, sessionId) }
            connection.commit()
        }
        val database = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        database.use {
            application {
                configureTransport()
                routing {
                    notificationRoutes(NotificationService(database, secret),
                        SessionAccessService(secret, JdbcSessionRepository(database)))
                }
            }
            val issuer = JwtTokenIssuer(secret)
            val now = Instant.now()
            fun tokenFor(id: UUID) = "Bearer " + issuer.issue(id, sessionIds.getValue(id), 0,
                now.minusSeconds(10), now.plusSeconds(900))
            HistoryFixture(client, ::connection, owner, other, tokenFor(owner), tokenFor(other)).test()
        }
    }

    private fun seedAccount(connection: Connection, user: UUID, session: UUID) {
        val phone = "+7" + user.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'fixture-hash','Fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, user)
            statement.setString(2, phone)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """INSERT INTO auth_sessions (id,user_id,created_at,expires_at)
               VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 hour')"""
        ).use { statement ->
            statement.setObject(1, session)
            statement.setObject(2, user)
            statement.executeUpdate()
        }
    }

    private class HistoryFixture(
        val client: HttpClient, val connection: () -> Connection,
        val owner: UUID, val other: UUID, val ownerToken: String, val otherToken: String,
    ) {
        fun seedEvent(recipient: UUID, createdAt: Instant, visible: Boolean = true, id: UUID = UUID.randomUUID()): UUID {
            connection().use { connection ->
                connection.prepareStatement(
                    """INSERT INTO notification_events (id,recipient_user_id,channel,event_type,dedupe_key,
                       display_snapshot,visible_in_app,push_requested,created_at)
                       VALUES (?,?,?,?,?,'{"title":"Fixture","body":"Test"}'::jsonb,?,?,?)"""
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, recipient)
                    statement.setString(3, if (visible) "PERSONAL" else "EXTRA")
                    statement.setString(4, if (visible) "chat_message" else "nearby_meeting")
                    statement.setString(5, id.toString())
                    statement.setBoolean(6, visible)
                    statement.setBoolean(7, !visible)
                    statement.setObject(8, createdAt.atOffset(ZoneOffset.UTC))
                    statement.executeUpdate()
                }
            }
            return id
        }

        suspend fun list(token: String, limit: Int, cursor: String? = null) = client.get("/api/v1/me/notifications") {
            header(HttpHeaders.Authorization, token)
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }

        suspend fun markRead(token: String, id: UUID) = client.put("/api/v1/me/notifications/$id/read") {
            header(HttpHeaders.Authorization, token)
        }

        fun readAt(id: UUID): Instant? = connection().use { connection ->
            connection.prepareStatement("SELECT read_at FROM notification_events WHERE id=?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getObject(1, OffsetDateTime::class.java)?.toInstant()
                }
            }
        }
    }

    private companion object {
        suspend fun HttpResponse.expectJson(expected: HttpStatusCode): JsonObject {
            assertEquals(expected, status)
            return Json.parseToJsonElement(bodyAsText()).jsonObject
        }

        suspend fun HttpResponse.expectInvalidCursor() {
            assertEquals("INVALID_CURSOR", expectJson(HttpStatusCode.BadRequest).errorCode())
        }

        fun JsonObject.errorCode(): String = getValue("error").jsonObject.getValue("code").jsonPrimitive.content

        fun JsonObject.ids(): List<String> = getValue("items").jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }
    }
}
