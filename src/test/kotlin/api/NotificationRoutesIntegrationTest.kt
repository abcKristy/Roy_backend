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
import io.ktor.client.request.get
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
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step7notifications$")))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val visible = UUID.randomUUID()
        val hidden = UUID.randomUUID()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'test-hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, actor); statement.setString(2, phone); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
                .use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
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
            val sessionId = UUID.randomUUID()
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == sessionId)
                        UserSelf(actor.toString(), "Test", null, "+79990000000", 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                routing { notificationRoutes(NotificationService(runtime, secret, "test"), sessions) }
            }
            val now = Instant.now()
            val token = "Bearer " + JwtTokenIssuer(secret).issue(actor, sessionId, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
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
            val location = client.put("/api/v1/me/notification-location") {
                header(HttpHeaders.Authorization, token); contentType(ContentType.Application.Json)
                setBody("""{"position":{"lat":55.75,"lon":37.62}}""")
            }
            assertEquals(HttpStatusCode.OK, location.status)
            assertTrue(location.bodyAsText().contains("55.75"))
            val invalidLocation = client.put("/api/v1/me/notification-location") {
                header(HttpHeaders.Authorization, token); contentType(ContentType.Application.Json)
                setBody("""{"position":{"lat":91.0,"lon":37.62}}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, invalidLocation.status)
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
            val disable = settings("""{"remindersPushEnabled":true,"personalPushEnabled":true,"extraPushEnabled":false,"extraInAppEnabled":false,"expectedRevision":1}""")
            assertEquals(HttpStatusCode.OK, disable.status)
            val cleared = client.get("/api/v1/me/notification-location") { header(HttpHeaders.Authorization, token) }
            assertEquals("null", Json.parseToJsonElement(cleared.bodyAsText()).jsonObject["position"].toString())
        } finally { runtime.close() }
    }
}
