package com.example.realtime

import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeRouteIntegrationTest {
    @Test
    fun `websocket authenticates and only subscribes to active memberships`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step5ws$")))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val pointId = UUID.randomUUID()
        val topicId = UUID.randomUUID()
        val chatId = UUID.randomUUID()
        val otherChatId = UUID.randomUUID()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            try {
                val phone = "+7" + userId.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                connection.prepareStatement(
                    """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,'test','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
                ).use { it.setObject(1, userId); it.setString(2, phone); it.executeUpdate() }
                connection.prepareStatement(
                    """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37.6,55.7),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
                ).use { it.setObject(1, pointId); it.setObject(2, userId); it.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
                ).use { it.setObject(1, topicId); it.setObject(2, pointId); it.executeUpdate() }
                connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())")
                    .use { it.setObject(1, chatId); it.setObject(2, topicId); it.executeUpdate() }
                connection.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())")
                    .use { it.setObject(1, chatId); it.setObject(2, userId); it.executeUpdate() }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
        val secret = "websocket-test-secret-at-least-thirty-two-bytes"
        val database = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        try {
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == userId && identity.sessionId == sessionId)
                        UserSelf(userId.toString(), "Test", null, "+79990000000", 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean = true
            })
            val hub = RealtimeHub(database, sessions)
            application {
                configureTransport()
                installRealtimeTransport()
                routing { realtimeRoutes(hub) }
            }
            val wsClient = createClient { install(WebSockets) }
            val denied = try {
                wsClient.webSocket("/api/v1/ws") { }
                null
            } catch (failure: Throwable) {
                failure
            }
            assertTrue(denied != null, "Unauthenticated WebSocket handshake unexpectedly succeeded")
            val now = Instant.now()
            val jwt = JwtTokenIssuer(secret).issue(userId, sessionId, 0, now.minusSeconds(10), now.plusSeconds(3600))
            wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
                val hello = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("hello", hello["type"]?.jsonPrimitive?.content)
                val requestId = UUID.randomUUID()
                send(Frame.Text("""{"v":1,"type":"subscribe","requestId":"$requestId","chatIds":["$chatId","$otherChatId"]}"""))
                val subscribed = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("subscribed", subscribed["type"]?.jsonPrimitive?.content)
                assertEquals(chatId.toString(), subscribed["acceptedChatIds"]!!.jsonArray.single().jsonPrimitive.content)
                assertEquals(otherChatId.toString(), subscribed["rejectedChatIds"]!!.jsonArray.single().jsonPrimitive.content)
                hub.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.CHAT_MESSAGE_CREATED,
                    RealtimeScopeKind.CHAT, chatId, Instant.now(), buildJsonObject { put("id", UUID.randomUUID().toString()) }))
                val event = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("chat.message.created", event["type"]?.jsonPrimitive?.content)
                send(Frame.Text("""{"v":1,"type":"delete_everything","requestId":"$requestId","chatIds":[]}"""))
                val invalid = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("INVALID_FIELD", invalid["code"]?.jsonPrimitive?.content)
                assertTrue(invalid["type"]?.jsonPrimitive?.content == "error")
                hub.closeRevokedSession(sessionId)
                val revoked = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("session.revoked", revoked["type"]?.jsonPrimitive?.content)
                assertEquals("logout", revoked["payload"]?.jsonObject?.get("reason")?.jsonPrimitive?.content)
                assertEquals(4401, closeReason.await()?.code?.toInt())
            }
        } finally {
            database.close()
        }
    }
}
