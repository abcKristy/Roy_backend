package com.example.acceptance

import com.example.config.ServerConfig
import com.example.configuredModule
import com.example.testing.testDatabaseUrl
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvpBackendChainIntegrationTest {
    @Test
    fun `local MVP connects real auth points chat meetings profile and notifications`() = runBlocking<Unit> {
        val database = testDatabaseUrl()
        require(database.endsWith("/roy_b5_mvp_chain"))
        val user = requireNotNull(System.getenv("DATABASE_USER"))
        val password = requireNotNull(System.getenv("DATABASE_PASSWORD"))
        val config = ServerConfig(database, user, password, "b5-chain-test-secret-for-local-use-only",
            draftPrivacyEnabled = true, publicBaseUrl = URI("http://127.0.0.1"))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { configuredModule(config) }
        server.start(wait = false)
        try {
            val api = Api(server.engine.resolvedConnectors().single().port)
            api.send("GET", "/health", 200)
            val viewport = "/points?west=37&south=55&east=38&north=56"
            assertTrue(api.send("GET", viewport, 200).obj()["items"]!!.jsonArray.isEmpty())
            api.send("POST", "/points", 401)
            val version = api.send("GET", "/legal/privacy", 200).obj().string("version")
            val author = api.register(version, "Synthetic Author")
            val peer = api.register(version, "Synthetic Peer")
            val nearby = api.register(version, "Synthetic Nearby")

            val centerRoute = "/me/notification-location"
            fun setNearbyExtra(enabled: Boolean, revision: Long): Long =
                api.send("PUT", "/me/notification-settings", 200, nearby.access, buildJsonObject {
                    put("remindersPushEnabled", true)
                    put("personalPushEnabled", true)
                    put("extraPushEnabled", false)
                    put("extraInAppEnabled", enabled)
                    put("expectedRevision", revision)
                }).obj().getValue("revision").jsonPrimitive.long

            fun putCenter(position: JsonObject): JsonObject =
                api.send("PUT", centerRoute, 200, nearby.access, buildJsonObject {
                    put("position", position)
                }).obj()

            fun assertCenterErased() {
                val center = api.send("GET", centerRoute, 200, nearby.access).obj()
                assertEquals(JsonNull, center.getValue("position"))
                assertEquals(JsonNull, center.getValue("updatedAt"))
                DriverManager.getConnection(database, user, password).use { db ->
                    assertCount(db, "SELECT count(*) FROM users WHERE id=? AND NOT EXISTS " +
                        "(SELECT 1 FROM notification_locations WHERE user_id=users.id)", nearby.id)
                }
            }

            val nearbyRevision = setNearbyExtra(true, 0)
            val position = buildJsonObject { put("lat", 55.75); put("lon", 37.62) }
            assertEquals(position, putCenter(position).getValue("position"))
            val lastPosition = buildJsonObject { put("lat", 55.7501); put("lon", 37.6201) }
            val lastCenter = putCenter(lastPosition)
            assertEquals(lastPosition, lastCenter.getValue("position"))
            Instant.parse(lastCenter.string("updatedAt"))
            assertEquals(lastCenter, api.send("GET", centerRoute, 200, nearby.access).obj())
            DriverManager.getConnection(database, user, password).use { db ->
                assertCount(db, "SELECT count(*) FROM notification_locations WHERE user_id=?", nearby.id)
            }

            val spoofedCenterRoute = "$centerRoute?userId=${nearby.id}"
            val peerCenter = api.send("GET", spoofedCenterRoute, 200, peer.access).obj()
            assertEquals(JsonNull, peerCenter.getValue("position"))
            api.send("PUT", centerRoute, 422, peer.access, buildJsonObject {
                put("userId", nearby.id)
                put("position", position)
            })
            api.send("DELETE", spoofedCenterRoute, 401)
            api.send("DELETE", spoofedCenterRoute, 204, peer.access)
            assertEquals(lastCenter, api.send("GET", centerRoute, 200, nearby.access).obj())
            val pointRequest = buildJsonObject {
                put("position", position)
                put("topic", buildJsonObject { put("presetKey", "football") })
                put("address", "Synthetic MVP address")
                put("addressSource", "manual")
                put("confirmNearby", false)
            }
            val pointKey = UUID.randomUUID()
            val created = api.send("POST", "/points", 201, author.access, pointRequest, pointKey).obj()
            val point = created.getValue("point").jsonObject.string("id")
            val chat = created.getValue("chat").jsonObject.string("id")
            assertEquals(created, api.send("POST", "/points", 201, author.access, pointRequest, pointKey).obj())
            assertTrue(api.send("GET", viewport, 200).obj()["items"]!!.jsonArray.any { it.jsonObject.string("id") == point })
            val denied = api.send("GET", "/chats/$chat", 403, nearby.access).obj()
            assertEquals("NOT_MEMBER", denied.getValue("error").jsonObject.string("code"))
            api.send("PUT", "/chats/$chat/membership", 200, peer.access)

            val wire = Wire()
            val socket = api.connect(peer.access, wire)
            try {
                assertEquals("hello", wire.await("hello").string("type"))
                val subscribe = buildJsonObject {
                    put("v", 1)
                    put("type", "subscribe")
                    put("requestId", UUID.randomUUID().toString())
                    put("chatIds", JsonArray(listOf(JsonPrimitive(chat))))
                }
                socket.sendText(subscribe.toString(), true).get(5, TimeUnit.SECONDS)
                val accepted = wire.await("subscribed").getValue("acceptedChatIds").jsonArray
                assertEquals(listOf(chat), accepted.map { it.jsonPrimitive.content })
                val messageBody = buildJsonObject { put("text", "Synthetic message") }
                val messageKey = UUID.randomUUID()
                val message = api.send("POST", "/chats/$chat/messages", 201, author.access, messageBody, messageKey).obj()
                assertEquals(message.string("id"), wire.await("chat.message.created").getValue("payload").jsonObject.string("id"))
                assertEquals(message, api.send("POST", "/chats/$chat/messages", 201, author.access, messageBody, messageKey).obj())
                assertTrue(api.send("GET", "/chats/$chat/messages", 200, peer.access).obj()["items"]!!.jsonArray
                    .any { it.jsonObject.string("id") == message.string("id") })

                val start = Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MINUTES)
                val proposal = api.send("POST", "/chats/$chat/proposals", 201, author.access, buildJsonObject {
                    put("startsAt", start.toString())
                    put("endsAt", start.plusSeconds(1800).toString())
                }, UUID.randomUUID()).obj()
                val proposalId = proposal.string("id")
                assertEquals(0, proposal.getValue("goingCount").jsonPrimitive.int)
                assertEquals(proposalId, wire.await("proposal.updated").getValue("payload").jsonObject.string("proposalId"))
                val vote = buildJsonObject { put("going", true) }
                api.send("PUT", "/proposals/$proposalId/vote", 200, author.access, vote)
                val ready = api.send("PUT", "/proposals/$proposalId/vote", 200, peer.access, vote).obj()
                assertEquals(2, ready.getValue("goingCount").jsonPrimitive.int)
                val booked = api.send("POST", "/proposals/$proposalId/booking", 201, author.access, buildJsonObject {
                    put("confirmOverlap", false)
                    put("expectedRevision", ready.getValue("revision").jsonPrimitive.long)
                }, UUID.randomUUID()).obj().getValue("booking").jsonObject
                assertEquals("active", booked.string("status"))

                val history = api.send("GET", "/me/notifications", 200, nearby.access).obj()
                val event = history.getValue("items").jsonArray.map { it.jsonObject }
                    .single { it.string("kind") == "nearby_meeting" }
                val target = event.getValue("target").jsonObject
                assertEquals(point, target.string("pointId"))
                assertEquals(JsonNull, target["chatId"])
                assertEquals(JsonNull, target["proposalId"])
                val read = "/me/notifications/${event.string("id")}/read"
                api.send("PUT", read, 404, author.access)
                val marked = api.send("PUT", read, 200, nearby.access).obj()
                Instant.parse(marked.string("readAt"))
                assertEquals(marked, api.send("PUT", read, 200, nearby.access).obj())

                val displayName = "Synthetic Updated"
                val updated = api.send("PATCH", "/me", 200, author.access, buildJsonObject {
                    put("displayName", displayName)
                }).obj()
                assertEquals(displayName, updated.string("displayName"))
                api.send("POST", "/me/password", 204, author.access, buildJsonObject {
                    put("currentPassword", FixturePassword)
                    put("newPassword", "SyntheticReplacement123!")
                })
                api.send("GET", "/me", 401, author.access)
                api.send("POST", "/auth/refresh", 401, body = buildJsonObject {
                    put("refreshToken", author.refresh)
                })
                val next = api.send("POST", "/auth/login", 200, body = buildJsonObject {
                    put("phone", author.phone)
                    put("password", "SyntheticReplacement123!")
                }).obj().string("accessToken")
                assertEquals(displayName, api.send("GET", "/me", 200, next).obj().string("displayName"))

                DriverManager.getConnection(database, user, password).use { db ->
                    assertCount(db, "SELECT count(*) FROM points WHERE id=? AND created_by_user_id=?", point, author.id)
                    assertCount(db, "SELECT count(*) FROM messages WHERE id=? AND chat_id=? AND author_user_id=?",
                        message.string("id"), chat, author.id)
                    assertCount(db, "SELECT count(*) FROM bookings WHERE id=? AND proposal_id=? AND status='ACTIVE'",
                        booked.string("id"), proposalId)
                    assertCount(db, "SELECT count(*) FROM notification_events WHERE id=? AND recipient_user_id=? AND read_at IS NOT NULL",
                        event.string("id"), nearby.id)
                }

                api.send("DELETE", centerRoute, 204, nearby.access)
                assertCenterErased()
                putCenter(lastPosition)
                val disabledRevision = setNearbyExtra(false, nearbyRevision)
                assertCenterErased()
                setNearbyExtra(true, disabledRevision)
                assertCenterErased()

                putCenter(lastPosition)
                api.send("POST", "/me/deletion", 204, nearby.access, buildJsonObject {
                    put("currentPassword", FixturePassword)
                    put("confirm", true)
                })
                api.send("GET", centerRoute, 401, nearby.access)
                DriverManager.getConnection(database, user, password).use { db ->
                    assertCount(db, "SELECT count(*) FROM users WHERE id=? AND deleted_at IS NOT NULL AND NOT EXISTS " +
                        "(SELECT 1 FROM notification_locations WHERE user_id=users.id)", nearby.id)
                }
            } finally {
                socket.abort()
            }
        } finally {
            server.stop(0, 5_000)
        }
    }

    private data class Actor(val id: String, val phone: String, val access: String, val refresh: String)

    private class Api(private val port: Int) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        fun send(method: String, path: String, status: Int, token: String? = null,
                 body: JsonObject? = null, key: UUID? = null): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port" + if (path == "/health") path else "/api/v1$path"))
                .timeout(Duration.ofSeconds(15))
            token?.let { request.header("Authorization", "Bearer $it") }
            key?.let { request.header("Idempotency-Key", it.toString()) }
            if (body != null) request.header("Content-Type", "application/json")
            val response = client.send(request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }
                ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(status, response.statusCode(), "$method $path")
            return response
        }

        fun register(version: String, name: String): Actor {
            val phone = "+7" + UUID.randomUUID().leastSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
            val result = send("POST", "/auth/register", 201, body = buildJsonObject {
                put("phone", phone)
                put("password", FixturePassword)
                put("displayName", name)
                put("ageYears", 20)
                put("acceptedPrivacyPolicyVersion", version)
            }).obj()
            return Actor(result.getValue("user").jsonObject.string("id"), phone,
                result.string("accessToken"), result.string("refreshToken"))
        }

        fun connect(token: String, wire: Wire): WebSocket = client.newWebSocketBuilder()
            .header("Authorization", "Bearer $token")
            .buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"), wire).get(5, TimeUnit.SECONDS)
    }

    private class Wire : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()

        override fun onOpen(socket: WebSocket) { socket.request(1) }

        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
            partial.append(data)
            if (last) {
                frames.add(partial.toString())
                partial.clear()
            }
            socket.request(1)
            return null
        }

        fun await(type: String): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val frame = frames.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    ?: error("Missing WebSocket event: $type")
                val event = Json.parseToJsonElement(frame).jsonObject
                if (event.string("type") == type) return event
            }
        }
    }

    private companion object {
        const val FixturePassword = "SyntheticMvpPassword123!"

        fun HttpResponse<String>.obj(): JsonObject = Json.parseToJsonElement(body()).jsonObject
        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

        fun assertCount(db: Connection, sql: String, vararg ids: String) {
            db.prepareStatement(sql).use { statement ->
                ids.forEachIndexed { index, id -> statement.setObject(index + 1, UUID.fromString(id)) }
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }
}
