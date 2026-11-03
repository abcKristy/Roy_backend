package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.realtime.RealtimeHub
import com.example.realtime.installRealtimeTransport
import com.example.realtime.realtimeRoutes
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatWriteRoutesIntegrationTest {
    @Test
    fun `send route persists once and rejects invalid content and foreign membership`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step5sendroute$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val secret = "chat-route-test-secret-at-least-32-bytes"
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            listOf(actor, recipient, outsider).forEach { insertUser(connection, it) }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, point); statement.setObject(2, actor); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())")
                .use { statement -> statement.setObject(1, topic); statement.setObject(2, point); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())")
                .use { statement -> statement.setObject(1, chat); statement.setObject(2, topic); statement.executeUpdate() }
            listOf(actor, recipient).forEach { id ->
                connection.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())")
                    .use { statement -> statement.setObject(1, chat); statement.setObject(2, id); statement.executeUpdate() }
            }
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        try {
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId in setOf(actor, recipient, outsider))
                        UserSelf(identity.userId.toString(), "Test", null, "+79990000000", 20, null, now.toString())
                    else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            val realtime = RealtimeHub(runtime, sessions)
            application {
                configureTransport()
                installRealtimeTransport()
                routing {
                    realtimeRoutes(realtime)
                    chatWriteRoutes(ChatSendService(runtime, MessageContentPolicy()), sessions, realtime)
                }
            }
            val issuer = JwtTokenIssuer(secret)
            val now = Instant.now()
            fun bearer(id: UUID) = "Bearer " + issuer.issue(id, UUID.randomUUID(), 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            val actorToken = bearer(actor)
            val outsiderToken = bearer(outsider)
            suspend fun sendMessage(token: String?, key: UUID?, text: String) = client.post("/api/v1/chats/$chat/messages") {
                token?.let { header(HttpHeaders.Authorization, it) }
                key?.let { header("Idempotency-Key", it.toString()) }
                contentType(ContentType.Application.Json)
                setBody(kotlinx.serialization.json.buildJsonObject { put("text", text) }.toString())
            }

            val rejected = sendMessage(actorToken, UUID.randomUUID(), "пиздец")
            assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
            assertTrue(rejected.bodyAsText().contains("MESSAGE_REJECTED_PATTERN"))
            val foreign = sendMessage(outsiderToken, UUID.randomUUID(), "Hello everyone")
            assertEquals(HttpStatusCode.Forbidden, foreign.status)
            assertTrue(foreign.bodyAsText().contains("NOT_MEMBER"))
            assertEquals(HttpStatusCode.Unauthorized, sendMessage(null, UUID.randomUUID(), "Hello").status)
            assertEquals(HttpStatusCode.BadRequest, sendMessage(actorToken, null, "Hello").status)

            val key = UUID.randomUUID()
            lateinit var firstBody: kotlinx.serialization.json.JsonObject
            val wsClient = createClient { install(WebSockets) }
            wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, bearer(recipient)) }) {
                assertEquals("hello", Json.parseToJsonElement((incoming.receive() as Frame.Text).readText())
                    .jsonObject["type"]?.jsonPrimitive?.content)
                val requestId = UUID.randomUUID()
                send(Frame.Text("""{"v":1,"type":"subscribe","requestId":"$requestId","chatIds":["$chat"]}"""))
                val subscribed = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("subscribed", subscribed["type"]?.jsonPrimitive?.content)
                val first = sendMessage(actorToken, key, "Hello everyone")
                assertEquals(HttpStatusCode.Created, first.status)
                firstBody = Json.parseToJsonElement(first.bodyAsText()).jsonObject
                val event = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals("chat.message.created", event["type"]?.jsonPrimitive?.content)
                assertEquals(firstBody, event["payload"]?.jsonObject)
            }
            assertEquals(chat.toString(), firstBody["chatId"]?.jsonPrimitive?.content)
            assertEquals("text", firstBody["type"]?.jsonPrimitive?.content)
            assertEquals("Hello everyone", firstBody["text"]?.jsonPrimitive?.content)
            assertEquals(HttpStatusCode.Created, sendMessage(actorToken, key, "Hello everyone").status)
            assertEquals(firstBody, Json.parseToJsonElement(sendMessage(actorToken, key, "Hello everyone").bodyAsText()).jsonObject)
            val reused = sendMessage(actorToken, key, "Different")
            assertEquals(HttpStatusCode.Conflict, reused.status)
            assertTrue(reused.bodyAsText().contains("IDEMPOTENCY_KEY_REUSED"))
            val flood = sendMessage(actorToken, UUID.randomUUID(), "Another")
            assertEquals(HttpStatusCode.TooManyRequests, flood.status)
            val retry = flood.headers[HttpHeaders.RetryAfter]?.toIntOrNull()
            assertTrue(retry != null && retry in 1..2)
            val floodBody = Json.parseToJsonElement(flood.bodyAsText()).jsonObject
            assertEquals(retry, floodBody["error"]?.jsonObject?.get("retryAfterSeconds")?.jsonPrimitive?.int)

            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(1, count(connection, "messages", "chat_id", chat))
                assertEquals(1, count(connection, "message_rate_events", "user_id", actor))
                assertEquals(1, count(connection, "notification_events", "recipient_user_id", recipient))
            }
        } finally {
            runtime.close()
        }
    }

    private fun insertUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, id); statement.executeUpdate() }
    }

    private fun count(connection: Connection, table: String, column: String, id: UUID): Int =
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $column=?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
}
