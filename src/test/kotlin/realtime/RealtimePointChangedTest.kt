package com.example.realtime

import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.persistence.DatabaseRuntime
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimePointChangedTest {
    @Test
    fun `committed point changes reach all point members without subscriptions and skip vanished points`() = testApplication {
        val firstMember = UUID.randomUUID()
        val otherTopicMember = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val chatId = UUID.randomUUID()
        val pointId = UUID.randomUUID()
        val updatedAt = Instant.parse("2026-11-04T09:00:00Z")
        var pointExists = true
        var recipientQueries = 0
        val db = object : DatabaseRuntime {
            override suspend fun isReady() = true
            override fun close() = Unit
            override suspend fun <T> withReadOnlyJdbcTransaction(block: (Connection) -> T): T =
                block(jdbcProxy(Connection::class.java) { name, args ->
                    when (name) {
                        "prepareStatement" -> {
                            val sql = args[0] as String
                            var parameter: Any? = null
                            jdbcProxy(PreparedStatement::class.java) { method, values ->
                                when (method) {
                                    "setObject" -> { parameter = values[1]; null }
                                    "executeQuery" -> {
                                        val rows: List<List<Any>> = when {
                                            sql.startsWith("SELECT point.id,point.updated_at") -> {
                                                assertEquals(chatId, parameter)
                                                if (pointExists) listOf(listOf(pointId, updatedAt.atOffset(ZoneOffset.UTC))) else emptyList()
                                            }
                                            sql.startsWith("SELECT DISTINCT membership.user_id") -> {
                                                assertEquals(pointId, parameter)
                                                assertTrue(sql.contains("topic.point_id=? AND membership.left_at IS NULL"))
                                                assertTrue(sql.contains("recipient.deleted_at IS NULL"))
                                                recipientQueries++
                                                listOf(listOf(firstMember), listOf(otherTopicMember))
                                            }
                                            else -> error("Unexpected query: $sql")
                                        }
                                        var index = -1
                                        jdbcProxy(ResultSet::class.java) { resultMethod, resultArgs ->
                                            when (resultMethod) {
                                                "next" -> ++index < rows.size
                                                "getObject" -> rows[index][(resultArgs[0] as Int) - 1]
                                                "close" -> null
                                                else -> error("Unexpected ResultSet method: $resultMethod")
                                            }
                                        }
                                    }
                                    "close" -> null
                                    else -> error("Unexpected statement method: $method")
                                }
                            }
                        }
                        else -> error("Unexpected connection method: $name")
                    }
                })
        }
        val secret = "synthetic-point-event-secret-at-least-32-bytes"
        val now = Instant.now()
        val identities = listOf(firstMember, otherTopicMember, outsider).associateWith {
            SessionIdentity(it, UUID.randomUUID(), 0)
        }
        val sessions = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                identities[identity.userId]?.takeIf { it == identity }?.let {
                    UserSelf(it.userId.toString(), "Synthetic User", null, "+12025550123", 18, null, updatedAt.toString())
                }
            override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
        })
        val tokens = identities.mapValues { (_, identity) ->
            "Bearer " + JwtTokenIssuer(secret).issue(identity.userId, identity.sessionId, 0,
                now.minusSeconds(1), now.plusSeconds(3600))
        }
        val hub = RealtimeHub(db, sessions)
        application {
            configureTransport()
            installRealtimeTransport()
            routing { realtimeRoutes(hub) }
        }
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, tokens.getValue(firstMember)) }) {
            val firstSocket = this
            assertEquals("hello", Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject["type"]?.jsonPrimitive?.content)
            wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, tokens.getValue(otherTopicMember)) }) {
                val otherSocket = this
                incoming.receive() // hello; neither member has subscribed to any chat.
                wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, tokens.getValue(outsider)) }) {
                    incoming.receive() // hello
                    hub.publishPointChangedForChat(chatId)
                    for ((socket, memberId) in listOf(firstSocket to firstMember, otherSocket to otherTopicMember)) {
                        val event = Json.parseToJsonElement(withTimeout(2_000) {
                            (socket.incoming.receive() as Frame.Text).readText()
                        }).jsonObject
                        assertEquals("point.changed", event["type"]?.jsonPrimitive?.content)
                        assertEquals("user", event["scope"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
                        assertEquals(memberId.toString(), event["scope"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                        val payload = event["payload"]!!.jsonObject
                        assertEquals(setOf("pointId", "updatedAt"), payload.keys)
                        assertEquals(pointId.toString(), payload["pointId"]?.jsonPrimitive?.content)
                        assertEquals(updatedAt.toString(), payload["updatedAt"]?.jsonPrimitive?.content)
                    }
                    assertNull(withTimeoutOrNull(100) { incoming.receive() }, "Outsider received point change")
                    pointExists = false
                    hub.publishPointChangedForChat(chatId)
                    assertEquals(1, recipientQueries, "Deleted point must skip recipient lookup")
                    assertNull(withTimeoutOrNull(100) { firstSocket.incoming.receive() })
                    assertNull(withTimeoutOrNull(100) { otherSocket.incoming.receive() })
                }
            }
        }
    }

    private fun <T> jdbcProxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        })
}
