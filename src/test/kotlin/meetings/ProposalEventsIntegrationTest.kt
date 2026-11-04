package com.example.meetings

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.notifications.NotificationService
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.realtime.*
import com.example.testing.testDatabaseUrl
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class ProposalEventsIntegrationTest {
    @Test
    fun `committed proposal emits isolated wire events and public nearby while replay and rollback stay silent`() = runBlocking<Unit> {
        Fixture().use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val hub = RealtimeHub(f.runtime, access)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport()
                installRealtimeTransport()
                configureRouting(f.runtime, sessionAccess = access, realtimeHub = hub,
                    proposalService = ProposalService(f.runtime, f.secret), bookingService = BookingService(f.runtime),
                    notificationService = NotificationService(f.runtime, f.secret))
            }
            val sockets = mutableListOf<WebSocket>()
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                fun request(actor: UUID, path: String, body: JsonObject? = null, key: UUID? = null): HttpRequest {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .timeout(Duration.ofSeconds(30)).header("Authorization", f.bearer(actor))
                    key?.let { builder.header("Idempotency-Key", it.toString()) }
                    return if (body == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
                }
                fun get(actor: UUID, path: String): JsonObject {
                    val result = client.send(request(actor, path), HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, result.statusCode())
                    return Json.parseToJsonElement(result.body()).jsonObject
                }
                fun connect(actor: UUID, chat: UUID?, accepted: Boolean = true): Wire {
                    val wire = Wire()
                    val socket = client.newWebSocketBuilder().header("Authorization", f.bearer(actor))
                        .buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"), wire).get(5, TimeUnit.SECONDS)
                    sockets += socket
                    assertEquals("hello", wire.next()["type"]?.jsonPrimitive?.content)
                    if (chat != null) {
                        val id = UUID.randomUUID()
                        socket.sendText(buildJsonObject {
                            put("v", 1); put("type", "subscribe"); put("requestId", id.toString())
                            put("chatIds", JsonArray(listOf(JsonPrimitive(chat.toString()))))
                        }.toString(), true).get(5, TimeUnit.SECONDS)
                        val reply = wire.next()
                        assertEquals(id.toString(), reply["requestId"]?.jsonPrimitive?.content)
                        assertEquals(listOf(chat.toString()), reply[if (accepted) "acceptedChatIds" else "rejectedChatIds"]!!
                            .jsonArray.map { it.jsonPrimitive.content })
                        assertTrue(reply[if (accepted) "rejectedChatIds" else "acceptedChatIds"]!!.jsonArray.isEmpty())
                    }
                    return wire
                }
                val authorWire = connect(f.actor, f.chat)
                val peerWire = connect(f.peer, f.chat)
                val adjacentWire = connect(f.peer, f.adjacentChat)
                val nearbyWire = connect(f.nearby, f.chat, accepted = false)
                suspend fun silence(actor: UUID, vararg wires: Wire) {
                    val marker = UUID.randomUUID()
                    hub.publishCommitted(RealtimeEvent(marker, RealtimeEventType.NOTIFICATION_CREATED,
                        RealtimeScopeKind.USER, actor, Instant.now(), buildJsonObject { put("marker", marker.toString()) }))
                    wires.forEach { it.silentThrough(marker) }
                }
                suspend fun allSilent() {
                    silence(f.actor, authorWire)
                    silence(f.peer, peerWire, adjacentWire)
                    silence(f.nearby, nearbyWire)
                }
                suspend fun isolated() {
                    silence(f.peer, adjacentWire)
                    // The same peer USER sentinel also reaches its target-chat socket.
                    assertEquals("notification.created", peerWire.next()["type"]?.jsonPrimitive?.content)
                    silence(f.nearby, nearbyWire)
                }
                fun assertEvents(proposal: JsonObject, expectedSeq: Long) {
                    val message = authorWire.next()
                    val update = authorWire.next()
                    assertEquals(message, peerWire.next())
                    assertEquals(update, peerWire.next())
                    assertEquals("chat.message.created", message["type"]?.jsonPrimitive?.content)
                    assertEquals("proposal.updated", update["type"]?.jsonPrimitive?.content)
                    for (event in listOf(message, update)) {
                        assertEquals(setOf("v", "eventId", "type", "scope", "occurredAt", "payload"), event.keys)
                        assertEquals(1, event["v"]!!.jsonPrimitive.int)
                        assertEquals(buildJsonObject { put("kind", "chat"); put("id", f.chat.toString()) }, event["scope"])
                        UUID.fromString(event["eventId"]!!.jsonPrimitive.content)
                        assertFalse(event.toString().contains("viewerGoing"))
                    }
                    val payload = message["payload"]!!.jsonObject
                    assertEquals(setOf("id", "chatId", "messageSeq", "type", "author", "text", "system", "createdAt"), payload.keys)
                    assertEquals("system", payload["type"]!!.jsonPrimitive.content)
                    assertEquals(f.chat.toString(), payload["chatId"]!!.jsonPrimitive.content)
                    assertEquals(JsonNull, payload["author"])
                    assertEquals(JsonNull, payload["text"])
                    assertEquals(expectedSeq, payload["messageSeq"]!!.jsonPrimitive.long)
                    assertEquals(buildJsonObject {
                        put("kind", "meeting_proposed"); put("actorUserId", f.actor.toString())
                        put("pointId", f.point.toString()); put("chatId", f.chat.toString())
                        put("proposalId", proposal.getValue("id")); put("bookingId", JsonNull)
                        put("startsAt", proposal.getValue("startsAt")); put("endsAt", proposal.getValue("endsAt"))
                        put("reason", JsonNull)
                    }, payload["system"])
                    assertEquals(buildJsonObject {
                        put("proposalId", proposal.getValue("id")); put("revision", proposal.getValue("revision"))
                        put("status", proposal.getValue("status")); put("goingCount", proposal.getValue("goingCount"))
                        put("bookingId", JsonNull)
                    }, update["payload"])
                    assertEquals(payload["createdAt"], message["occurredAt"])
                    assertEquals(proposal["updatedAt"], update["occurredAt"])
                    f.connection().use { c ->
                        c.prepareStatement("SELECT m.id,m.message_seq,p.id,p.revision FROM messages m JOIN meeting_proposals p ON p.id=(m.system_payload->>'proposalId')::uuid WHERE m.chat_id=? AND m.message_seq=?").use { s ->
                            s.setObject(1, f.chat); s.setLong(2, expectedSeq)
                            s.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                assertEquals(payload["id"]!!.jsonPrimitive.content, rows.getObject(1).toString())
                                assertEquals(expectedSeq, rows.getLong(2))
                                assertEquals(proposal["id"]!!.jsonPrimitive.content, rows.getObject(3).toString())
                                assertEquals(proposal["revision"]!!.jsonPrimitive.long, rows.getLong(4))
                            }
                        }
                    }
                }
                fun body(offset: Long) = buildJsonObject {
                    val start = Instant.now().plusSeconds(7200 + offset).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                    put("startsAt", start.toString()); put("endsAt", start.plusSeconds(3600).toString())
                }
                val command = body(0)
                val key = UUID.randomUUID()
                assertEquals(State(0, 0, 0, 0, 0, 0), f.state())
                lateinit var pending: CompletableFuture<HttpResponse<String>>
                f.connection().use { gate ->
                    gate.autoCommit = false
                    gate.createStatement().use { it.execute("LOCK TABLE operation_idempotency IN SHARE MODE") }
                    pending = client.sendAsync(request(f.actor, "/api/v1/chats/${f.chat}/proposals", command, key), HttpResponse.BodyHandlers.ofString())
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var waiting = false
                    while (!waiting && System.nanoTime() < deadline) {
                        waiting = f.connection().use { c -> c.createStatement().use { s ->
                            s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation='operation_idempotency'::regclass AND mode='RowExclusiveLock' AND NOT granted)").use { rows ->
                                check(rows.next()); rows.getBoolean(1)
                            }
                        } }
                        if (!waiting) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
                    }
                    assertTrue(waiting, "Actual final outcome INSERT must be blocked before commit")
                    assertFalse(pending.isDone)
                    assertEquals(State(0, 0, 0, 0, 0, 0), f.state())
                    allSilent()
                    gate.commit()
                }
                val response = pending.get(10, TimeUnit.SECONDS)
                assertEquals(201, response.statusCode())
                val proposal = Json.parseToJsonElement(response.body()).jsonObject
                assertEquals("false", proposal["viewerGoing"]!!.jsonPrimitive.content)
                assertEquals(State(1, 0, 1, 1, 1, 1), f.state())
                assertEvents(proposal, 1)
                isolated()
                val publicNearby = get(f.nearby, "/api/v1/me/notifications")["items"]!!.jsonArray.single().jsonObject
                assertEquals("nearby_meeting", publicNearby["kind"]!!.jsonPrimitive.content)
                assertEquals(buildJsonObject {
                    put("pointId", f.point.toString()); put("chatId", JsonNull); put("proposalId", JsonNull)
                }, publicNearby["target"])
                assertFalse(publicNearby.toString().contains(f.chat.toString()))
                assertFalse(publicNearby.toString().contains(proposal["id"]!!.jsonPrimitive.content))
                f.assertNearby(proposal["id"]!!.jsonPrimitive.content)
                for (member in listOf(f.actor, f.peer)) assertTrue(get(member, "/api/v1/me/notifications")["items"]!!.jsonArray.isEmpty())
                val replay = client.send(request(f.actor, "/api/v1/chats/${f.chat}/proposals", command, key), HttpResponse.BodyHandlers.ofString())
                assertEquals(201, replay.statusCode())
                assertEquals(proposal, Json.parseToJsonElement(replay.body()))
                assertEquals(State(1, 0, 1, 1, 1, 1), f.state())
                allSilent()

                val retryKey = UUID.randomUUID()
                val retryCommand = body(3600)
                val beforeFault = f.state()
                f.installFault()
                try {
                    val failed = client.send(request(f.actor, "/api/v1/chats/${f.chat}/proposals", retryCommand, retryKey), HttpResponse.BodyHandlers.ofString())
                    assertEquals(500, failed.statusCode())
                    assertEquals("INTERNAL_ERROR", Json.parseToJsonElement(failed.body()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertEquals(beforeFault, f.state())
                    assertEquals(listOf(publicNearby), get(f.nearby, "/api/v1/me/notifications")["items"]!!.jsonArray.toList())
                    allSilent()
                } finally { f.dropFault() }
                val retried = client.send(request(f.actor, "/api/v1/chats/${f.chat}/proposals", retryCommand, retryKey), HttpResponse.BodyHandlers.ofString())
                assertEquals(201, retried.statusCode())
                val retriedBody = Json.parseToJsonElement(retried.body()).jsonObject
                assertEvents(retriedBody, 2)
                assertEquals(State(2, 0, 2, 2, 2, 2), f.state())
                f.assertNearby(retriedBody["id"]!!.jsonPrimitive.content)
                isolated()
                allSilent()
            } finally {
                sockets.forEach { it.abort() }
                server.stop(0, 1000)
            }
        }
    }

    private data class State(val proposals: Int, val votes: Int, val messages: Int, val outcomes: Int, val notifications: Int, val seq: Int)

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_events$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-proposal-events-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL, databaseMaxPoolSize = 8,
            databaseStatementTimeoutMs = 30_000, databaseLockTimeoutMs = 20_000)).start()
        val actor = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val nearby = UUID.randomUUID()
        private val people = listOf(actor, peer, nearby)
        private val sessions = people.associateWith { UUID.randomUUID() }
        val point = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val adjacentChat = UUID.randomUUID()
        private val suffix = actor.toString().replace("-", "")
        private val faultFunction = "m4_proposal_fault_$suffix"
        private val faultTrigger = "m4_proposal_fault_$suffix"

        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Events fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,extra_in_app_enabled,updated_at) VALUES (?,true,clock_timestamp())", id)
                        execute(c, "INSERT INTO notification_locations(user_id,position,updated_at) VALUES (?,ST_SetSRID(ST_MakePoint(37.6201,55.7501),4326)::geography,clock_timestamp())", id)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic events fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, actor)
                    for ((index, id) in listOf(chat, adjacentChat).withIndex()) {
                        val topic = UUID.randomUUID()
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())", topic, point, listOf("football", "basketball")[index])
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", id, topic)
                        for (member in if (id == chat) listOf(actor, peer) else listOf(peer)) {
                            execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", id, member)
                        }
                    }
                    c.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun bearer(id: UUID): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(id, sessions.getValue(id), 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun state(): State = connection().use { c ->
            fun count(sql: String, id: UUID): Int = c.prepareStatement(sql).use { s ->
                s.setObject(1, id); s.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
            }
            State(count("SELECT count(*) FROM meeting_proposals WHERE chat_id=?", chat),
                count("SELECT count(*) FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=?", chat),
                count("SELECT count(*) FROM messages WHERE chat_id=?", chat),
                count("SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", actor),
                count("SELECT count(*) FROM notification_events WHERE point_id=?", point),
                count("SELECT last_message_seq FROM chats WHERE id=?", chat))
        }
        fun assertNearby(proposalId: String) = connection().use { c ->
            c.prepareStatement("SELECT recipient_user_id,proposal_id,dedupe_key FROM notification_events WHERE proposal_id=?").use { s ->
                s.setObject(1, UUID.fromString(proposalId))
                s.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(nearby, rows.getObject(1, UUID::class.java))
                    assertEquals(proposalId, rows.getObject(2).toString()); assertEquals("nearby:$proposalId", rows.getString(3))
                    assertFalse(rows.next())
                }
            }
        }
        fun installFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE FUNCTION $faultFunction() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic proposal outcome fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE TRIGGER $faultTrigger BEFORE INSERT ON operation_idempotency FOR EACH ROW WHEN (NEW.actor_user_id='$actor'::uuid AND NEW.operation_type='create_proposal') EXECUTE FUNCTION $faultFunction()")
        } }
        fun dropFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("DROP TRIGGER IF EXISTS $faultTrigger ON operation_idempotency")
            s.execute("DROP FUNCTION IF EXISTS $faultFunction()")
        } }
        override fun close() {
            try {
                dropFault()
                connection().use { c ->
                    c.autoCommit = false
                    execute(c, "DELETE FROM points WHERE id=?", point)
                    for (id in people) execute(c, "DELETE FROM users WHERE id=?", id)
                    c.commit()
                }
            } finally { runtime.close() }
        }
    }

    private class Wire : WebSocket.Listener {
        private val messages = LinkedBlockingQueue<JsonObject>()
        private val buffer = StringBuilder()
        override fun onOpen(socket: WebSocket) { socket.request(1) }
        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data)
            if (last) { messages.add(Json.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }
            socket.request(1)
            return CompletableFuture.completedFuture(null)
        }
        fun next(): JsonObject = assertNotNull(messages.poll(5, TimeUnit.SECONDS), "Expected native WS frame")
        fun silentThrough(marker: UUID) {
            val frame = next()
            assertEquals(marker.toString(), frame["eventId"]?.jsonPrimitive?.content, "Unexpected event before ordered USER marker")
            assertEquals("notification.created", frame["type"]?.jsonPrimitive?.content)
            assertNull(messages.poll(250, TimeUnit.MILLISECONDS), "Unexpected event after marker (bounded 250ms observation)")
        }
    }

    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any) {
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
                s.executeUpdate()
            }
        }
    }
}
