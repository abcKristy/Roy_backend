package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.DraftPrivacyPolicy
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.notifications.NotificationService
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.util.PGobject
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationRoutesIntegrationTest {
    @Test
    fun `settings center and voluntary location enforce HTTP contract`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step7notifications$")))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val other = UUID.randomUUID()
        val sessionIds = listOf(actor, other).associateWith { UUID.randomUUID() }
        val secondActorSession = UUID.randomUUID()
        val visible = UUID.randomUUID()
        val hidden = UUID.randomUUID()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            for ((id, version) in listOf(actor to DraftPrivacyPolicy.VERSION, other to "old-draft")) {
                val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                connection.prepareStatement(
                    """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,'test-hash','Test',20,?,clock_timestamp(),clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, id); statement.setString(2, phone); statement.setString(3, version)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO notification_settings (user_id,extra_push_enabled,updated_at) VALUES (?,?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, id); statement.setBoolean(2, id == other); statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                """INSERT INTO auth_sessions (id,user_id,created_at,expires_at)
                   VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 hour')"""
            ).use { statement ->
                for ((userId, sessionId) in sessionIds.toList() + (actor to secondActorSession)) {
                    statement.setObject(1, sessionId)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
            }
            for ((id, channel, kind, show) in listOf(
                listOf(visible, "PERSONAL", "chat_message", true),
                listOf(hidden, "EXTRA", "nearby_meeting", false),
            )) {
                connection.prepareStatement(
                    """INSERT INTO notification_events (id,recipient_user_id,channel,event_type,dedupe_key,
                       display_snapshot,visible_in_app,push_requested,created_at)
                       VALUES (?,?,?,?,?,?,?,?,clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, id); statement.setObject(2, actor); statement.setString(3, channel as String)
                    statement.setString(4, kind as String); statement.setString(5, id.toString())
                    statement.setObject(6, PGobject().apply { type = "jsonb"; value = "{\"title\":\"Test\",\"body\":\"Body\"}" })
                    statement.setBoolean(7, show as Boolean); statement.setBoolean(8, !show)
                    statement.executeUpdate()
                }
            }
            connection.commit()
        }
        val secret = "notification-routes-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        try {
            val sessions = SessionAccessService(secret, JdbcSessionRepository(runtime))
            application {
                configureTransport()
                routing { notificationRoutes(NotificationService(runtime, secret, DraftPrivacyPolicy.VERSION), sessions) }
            }
            val now = Instant.now()
            fun tokenFor(id: UUID, sessionId: UUID = sessionIds.getValue(id)) = "Bearer " + JwtTokenIssuer(secret).issue(id, sessionId, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            val token = tokenFor(actor)
            val locationPath = "/api/v1/me/notification-location"
            suspend fun putLocation(lat: String, tokenValue: String = token) = client.put(locationPath) {
                header(HttpHeaders.Authorization, tokenValue); contentType(ContentType.Application.Json)
                setBody("""{"position":{"lat":$lat,"lon":37.62}}""")
            }
            suspend fun getLocation(tokenValue: String = token): JsonObject {
                val response = client.get(locationPath) { header(HttpHeaders.Authorization, tokenValue) }
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            suspend fun settings(body: String) = client.put("/api/v1/me/notification-settings") {
                header(HttpHeaders.Authorization, token); contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me/notifications").status)
            val initial = client.get("/api/v1/me/notification-settings") { header(HttpHeaders.Authorization, token) }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertEquals("0", Json.parseToJsonElement(initial.bodyAsText()).jsonObject["revision"]?.toString())
            val enable = settings("""{"remindersPushEnabled":true,"personalPushEnabled":true,"extraPushEnabled":true,"extraInAppEnabled":false,"expectedRevision":0}""")
            assertEquals(HttpStatusCode.OK, enable.status)
            assertEquals(HttpStatusCode.Conflict, settings("""{"remindersPushEnabled":true,"personalPushEnabled":true,"extraPushEnabled":true,"extraInAppEnabled":false,"expectedRevision":0}""").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(locationPath).status)
            assertEquals(JsonNull, getLocation().getValue("position"))
            val location = putLocation("55.75")
            assertEquals(HttpStatusCode.OK, location.status)
            assertEquals("no-store", location.headers[HttpHeaders.CacheControl])
            val saved = Json.parseToJsonElement(location.bodyAsText()).jsonObject
            assertEquals("55.75", saved.getValue("position").jsonObject.getValue("lat").jsonPrimitive.content)
            assertTrue(!Instant.parse(saved.getValue("updatedAt").jsonPrimitive.content).isBefore(now.minusSeconds(1)))
            assertEquals(HttpStatusCode.UnprocessableEntity, putLocation("91.0").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, putLocation("\"55.0\"").status)
            val stale = putLocation("55.75", tokenFor(other))
            assertEquals(HttpStatusCode.Forbidden, stale.status)
            assertEquals("POLICY_NOT_ACCEPTED", Json.parseToJsonElement(stale.bodyAsText()).jsonObject
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(JsonNull, getLocation(tokenFor(other)).getValue("position"))
            assertEquals(saved, getLocation())
            repeat(2) {
                assertEquals(HttpStatusCode.NoContent, client.delete(locationPath) { header(HttpHeaders.Authorization, token) }.status)
            }
            assertEquals(JsonNull, getLocation().getValue("position"))
            assertEquals(HttpStatusCode.OK, putLocation("55.80").status)
            val page = client.get("/api/v1/me/notifications") { header(HttpHeaders.Authorization, token) }
            assertEquals(HttpStatusCode.OK, page.status)
            val items = Json.parseToJsonElement(page.bodyAsText()).jsonObject["items"]!!.jsonArray
            assertEquals(listOf(visible.toString()), items.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            assertEquals(HttpStatusCode.NotFound, client.put("/api/v1/me/notifications/$hidden/read") {
                header(HttpHeaders.Authorization, token)
            }.status)
            assertEquals(HttpStatusCode.OK, client.put("/api/v1/me/notifications/$visible/read") {
                header(HttpHeaders.Authorization, token)
            }.status)
            suspend fun extra(push: Boolean, inApp: Boolean, revision: Int) = settings(
                """{"remindersPushEnabled":true,"personalPushEnabled":true,"extraPushEnabled":$push,"extraInAppEnabled":$inApp,"expectedRevision":$revision}"""
            )
            val retainedCenter = getLocation()
            assertEquals(HttpStatusCode.OK, extra(push = true, inApp = true, revision = 1).status)
            assertEquals(retainedCenter, getLocation())
            assertEquals(HttpStatusCode.OK, extra(push = false, inApp = true, revision = 2).status)
            assertEquals(retainedCenter, getLocation())
            assertEquals(HttpStatusCode.Conflict, extra(push = false, inApp = false, revision = 2).status)
            assertEquals(retainedCenter, getLocation())
            assertEquals(HttpStatusCode.OK, extra(push = false, inApp = false, revision = 3).status)
            assertEquals(JsonNull, getLocation().getValue("position"))
            assertEquals(HttpStatusCode.UnprocessableEntity, putLocation("55.80").status)
            assertEquals(HttpStatusCode.OK, extra(push = true, inApp = false, revision = 4).status)
            assertEquals(JsonNull, getLocation().getValue("position"))
            assertEquals(HttpStatusCode.OK, putLocation("55.81").status)
            val centerBeforeLogout = getLocation()
            sessions.logout(token)
            assertEquals(HttpStatusCode.Unauthorized, client.get(locationPath) {
                header(HttpHeaders.Authorization, token)
            }.status)
            assertEquals(centerBeforeLogout, getLocation(tokenFor(actor, secondActorSession)))
        } finally { runtime.close() }
    }
}
