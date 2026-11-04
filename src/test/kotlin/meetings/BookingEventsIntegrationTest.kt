package com.example.meetings

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
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

class BookingEventsIntegrationTest {
    @Test
    fun `booking wire events follow commit membership and replay boundaries`() = runBlocking<Unit> {
        Fixture().use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val hub = RealtimeHub(f.runtime, access)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport(); installRealtimeTransport()
                configureRouting(f.runtime, sessionAccess = access, realtimeHub = hub,
                    proposalService = ProposalService(f.runtime, f.secret), bookingService = BookingService(f.runtime))
            }
            val sockets = mutableListOf<WebSocket>()
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                fun command(actor: UUID, proposal: UUID, key: UUID? = null): HttpRequest {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/$proposal${if (key == null) "" else "/booking"}"))
                        .timeout(Duration.ofSeconds(35)).header("Authorization", f.bearer(actor))
                    return if (key == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                        .header("Idempotency-Key", key.toString()).POST(HttpRequest.BodyPublishers.ofString("""{"confirmOverlap":false,"expectedRevision":2}""")).build()
                }
                fun parse(response: HttpResponse<String>, expected: Int): JsonObject {
                    assertEquals(expected, response.statusCode())
                    return Json.parseToJsonElement(response.body()).jsonObject
                }
                fun request(actor: UUID, proposal: UUID, key: UUID? = null, expected: Int = if (key == null) 200 else 201) =
                    parse(client.send(command(actor, proposal, key), HttpResponse.BodyHandlers.ofString()), expected)
                fun connect(actor: UUID, chat: UUID, accepted: Boolean = true): Wire {
                    val wire = Wire()
                    val socket = client.newWebSocketBuilder().header("Authorization", f.bearer(actor))
                        .buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"), wire).get(5, TimeUnit.SECONDS)
                    sockets += socket
                    assertEquals("hello", wire.next()["type"]?.jsonPrimitive?.content)
                    val id = UUID.randomUUID()
                    socket.sendText(buildJsonObject {
                        put("v", 1); put("type", "subscribe"); put("requestId", id.toString())
                        put("chatIds", JsonArray(listOf(JsonPrimitive(chat.toString()))))
                    }.toString(), true).get(5, TimeUnit.SECONDS)
                    val reply = wire.next()
                    assertEquals(id.toString(), reply["requestId"]?.jsonPrimitive?.content)
                    assertEquals(listOf(JsonPrimitive(chat.toString())), reply[if (accepted) "acceptedChatIds" else "rejectedChatIds"]!!.jsonArray.toList())
                    assertTrue(reply[if (accepted) "rejectedChatIds" else "acceptedChatIds"]!!.jsonArray.isEmpty())
                    return wire
                }
                val bookerWire = connect(f.booker, f.chat)
                val peerWire = connect(f.peer, f.chat)
                val adjacentWire = connect(f.peer, f.adjacentChat)
                val outsiderWire = connect(f.outsider, f.chat, accepted = false)
                suspend fun silence() {
                    for ((actor, wires) in listOf(f.booker to listOf(bookerWire), f.peer to listOf(peerWire, adjacentWire), f.outsider to listOf(outsiderWire))) {
                        val id = UUID.randomUUID()
                        hub.publishCommitted(RealtimeEvent(id, RealtimeEventType.NOTIFICATION_CREATED,
                            RealtimeScopeKind.USER, actor, Instant.now(), buildJsonObject { put("marker", id.toString()) }))
                        wires.forEach { it.silentThrough(id) }
                    }
                }
                fun events(proposal: JsonObject, expectedSeq: Long) {
                    val expectedBooking = proposal["booking"]!!.jsonObject
                    val first = List(3) { bookerWire.next() }.associateBy { it["eventId"]!!.jsonPrimitive.content }
                    val second = List(3) { peerWire.next() }.associateBy { it["eventId"]!!.jsonPrimitive.content }
                    assertEquals(3, first.size); assertEquals(first, second)
                    assertEquals(setOf("chat.message.created", "proposal.updated", "booking.changed"), first.values.map { it["type"]!!.jsonPrimitive.content }.toSet())
                    for (event in first.values) {
                        assertEquals(setOf("v", "eventId", "type", "scope", "occurredAt", "payload"), event.keys)
                        assertEquals(1, event["v"]!!.jsonPrimitive.int)
                        UUID.fromString(event["eventId"]!!.jsonPrimitive.content)
                        assertEquals(buildJsonObject { put("kind", "chat"); put("id", f.chat.toString()) }, event["scope"])
                        assertFalse(event.toString().contains("viewerGoing"))
                        val payload = event["payload"]!!.jsonObject
                        when (event["type"]!!.jsonPrimitive.content) {
                            "chat.message.created" -> {
                                assertEquals(setOf("id", "chatId", "messageSeq", "type", "author", "text", "system", "createdAt"), payload.keys)
                                assertEquals("system", payload["type"]!!.jsonPrimitive.content)
                                assertEquals(JsonNull, payload["author"]); assertEquals(JsonNull, payload["text"])
                                assertEquals(expectedSeq, payload["messageSeq"]!!.jsonPrimitive.long)
                                assertEquals(f.chat.toString(), payload["chatId"]!!.jsonPrimitive.content)
                                assertEquals(buildJsonObject {
                                    put("kind", "meeting_booked"); put("actorUserId", f.booker.toString())
                                    put("pointId", f.point.toString()); put("chatId", f.chat.toString())
                                    put("proposalId", proposal.getValue("id")); put("bookingId", expectedBooking.getValue("id"))
                                    put("startsAt", proposal.getValue("startsAt")); put("endsAt", proposal.getValue("endsAt")); put("reason", JsonNull)
                                }, payload["system"])
                                assertEquals(payload["createdAt"], event["occurredAt"])
                                f.assertCommittedMessage(payload, proposal)
                            }
                            "proposal.updated" -> {
                                assertEquals(buildJsonObject {
                                    put("proposalId", proposal.getValue("id")); put("revision", proposal.getValue("revision"))
                                    put("status", "booked"); put("goingCount", 2); put("bookingId", expectedBooking.getValue("id"))
                                }, payload)
                                assertEquals(proposal["updatedAt"], event["occurredAt"])
                            }
                            "booking.changed" -> {
                                assertEquals(buildJsonObject {
                                    put("proposalId", proposal.getValue("id")); put("bookingId", expectedBooking.getValue("id"))
                                    put("status", "active"); put("cancelReason", JsonNull)
                                }, payload)
                                assertEquals(proposal["updatedAt"], event["occurredAt"])
                            }
                        }
                    }
                }
                val key = UUID.randomUUID()
                val baseline = f.state()
                val denied = request(f.outsider, f.first, UUID.randomUUID(), 403)
                assertEquals("NOT_MEMBER", denied["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertEquals(baseline, f.state())
                lateinit var pending: CompletableFuture<HttpResponse<String>>
                f.connection().use { gate ->
                    gate.autoCommit = false
                    gate.createStatement().use { it.execute("LOCK TABLE operation_idempotency IN SHARE MODE") }
                    pending = client.sendAsync(command(f.booker, f.first, key), HttpResponse.BodyHandlers.ofString())
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (!blocked && System.nanoTime() < deadline) {
                        blocked = f.connection().use { c -> c.createStatement().use { s ->
                            s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation='operation_idempotency'::regclass AND mode='RowExclusiveLock' AND NOT granted)").use { r -> check(r.next()); r.getBoolean(1) }
                        } }
                        if (!blocked) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
                    }
                    assertTrue(blocked); assertFalse(pending.isDone)
                    assertEquals(baseline, f.state())
                    val old = request(f.peer, f.first)
                    assertEquals("ready_to_book", old["status"]!!.jsonPrimitive.content); assertEquals(JsonNull, old["booking"])
                    silence(); assertFalse(pending.isDone)
                    gate.commit()
                }
                val created = parse(pending.get(15, TimeUnit.SECONDS), 201)
                events(created, 1)
                assertEquals(created, request(f.booker, f.first))
                silence()
                val bookingId = created["booking"]!!.jsonObject["id"]!!.jsonPrimitive.content
                val outsiderRead = request(f.outsider, f.first, expected = 403)
                assertFalse(outsiderRead.toString().contains(bookingId))
                val committed = f.state()
                assertEquals(created, request(f.booker, f.first, key))
                assertEquals(committed, f.state()); silence()

                val retryKey = UUID.randomUUID()
                val beforeFault = f.state()
                f.installDeferredFault(retryKey)
                try {
                    val failed = request(f.booker, f.second, retryKey, 500)
                    assertEquals("INTERNAL_ERROR", failed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertEquals(beforeFault, f.state())
                    val old = request(f.booker, f.second)
                    assertEquals("ready_to_book", old["status"]!!.jsonPrimitive.content); assertEquals(JsonNull, old["booking"])
                    silence()
                } finally { f.dropFault() }
                val retried = request(f.booker, f.second, retryKey)
                events(retried, 2)
                val afterRetry = f.state()
                assertEquals(retried, request(f.booker, f.second, retryKey))
                assertEquals(afterRetry, f.state()); silence()
            } finally { sockets.forEach { it.abort() }; server.stop(0, 1000) }
        }
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_booking_events$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-booking-events-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL,
            databaseMaxPoolSize = 8, databaseLockTimeoutMs = 25_000, databaseStatementTimeoutMs = 30_000)).start()
        val peer = UUID.randomUUID(); val booker = UUID.randomUUID(); val outsider = UUID.randomUUID()
        private val people = listOf(peer, booker, outsider)
        private val sessions = people.associateWith { UUID.randomUUID() }
        val point = UUID.randomUUID(); val chat = UUID.randomUUID(); val adjacentChat = UUID.randomUUID()
        val first = UUID.randomUUID(); val second = UUID.randomUUID()
        private val faultName = "m4_booking_ws_fault_" + booker.toString().replace("-", "")
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Booking events fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic booking events fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, peer)
                    for ((index, id) in listOf(chat, adjacentChat).withIndex()) {
                        val topic = UUID.randomUUID()
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())", topic, point, if (index == 0) "football" else "basketball")
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", id, topic)
                        for (member in if (id == chat) listOf(peer, booker) else listOf(peer)) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", id, member)
                    }
                    for ((index, id) in listOf(first, second).withIndex()) {
                        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                            VALUES (?,?,?,clock_timestamp()+?::interval,clock_timestamp()+?::interval,2,clock_timestamp(),clock_timestamp())""", id, chat, peer,
                            "${24 + index * 2} hours", "${25 + index * 2} hours")
                        for (voter in listOf(peer, booker)) execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", id, voter)
                    }
                    c.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }
        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun bearer(actor: UUID): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, sessions.getValue(actor), 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun state(): List<List<String?>> = connection().use { c ->
            c.prepareStatement("""SELECT b.id::text,b.booking_order::text,b.status,b.booked_by_user_id::text FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id=?
                UNION ALL SELECT id::text,revision::text,lifecycle,updated_at::text FROM meeting_proposals WHERE chat_id=?
                UNION ALL SELECT id::text,message_seq::text,system_type,system_payload::text FROM messages WHERE chat_id=?
                UNION ALL SELECT id::text,recipient_user_id::text,event_type,dedupe_key FROM notification_events WHERE chat_id=?
                UNION ALL SELECT actor_user_id::text,idempotency_key,operation_type,response_snapshot::text FROM operation_idempotency WHERE actor_user_id=?
                UNION ALL SELECT id::text,last_message_seq::text,'chat','' FROM chats WHERE id=?
                UNION ALL SELECT id::text,updated_at::text,'point','' FROM points WHERE id=? ORDER BY 1""").use { s ->
                listOf(chat, chat, chat, chat, booker, chat, point).forEachIndexed { index, id -> s.setObject(index + 1, id) }
                s.executeQuery().use { r -> buildList { while (r.next()) add((1..4).map { r.getString(it) }) } }
            }
        }
        fun assertCommittedMessage(message: JsonObject, proposal: JsonObject) = connection().use { c ->
            c.prepareStatement("SELECT m.message_seq,m.system_payload,p.revision,b.id,b.status FROM messages m JOIN meeting_proposals p ON p.id=(m.system_payload->>'proposalId')::uuid JOIN bookings b ON b.id=(m.system_payload->>'bookingId')::uuid WHERE m.id=? AND m.chat_id=?").use { s ->
                s.setObject(1, UUID.fromString(message["id"]!!.jsonPrimitive.content)); s.setObject(2, chat)
                s.executeQuery().use { r ->
                    assertTrue(r.next()); assertEquals(message["messageSeq"]!!.jsonPrimitive.long, r.getLong(1))
                    assertEquals(message["system"], Json.parseToJsonElement(r.getString(2)))
                    assertEquals(proposal["revision"]!!.jsonPrimitive.long, r.getLong(3))
                    assertEquals(proposal["booking"]!!.jsonObject["id"]!!.jsonPrimitive.content, r.getObject(4).toString()); assertEquals("ACTIVE", r.getString(5))
                }
            }
        }
        fun installDeferredFault(key: UUID) = connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE FUNCTION $faultName() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic booking wire COMMIT fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE CONSTRAINT TRIGGER $faultName AFTER INSERT ON operation_idempotency DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.actor_user_id='$booker'::uuid AND NEW.operation_type='book_meeting' AND NEW.idempotency_key='$key') EXECUTE FUNCTION $faultName()")
        } }
        fun dropFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("DROP TRIGGER IF EXISTS $faultName ON operation_idempotency"); s.execute("DROP FUNCTION IF EXISTS $faultName()")
        } }
        override fun close() {
            try {
                dropFault()
                connection().use { c ->
                    c.autoCommit = false; execute(c, "DELETE FROM points WHERE id=?", point)
                    for (id in people) execute(c, "DELETE FROM users WHERE id=?", id)
                    c.commit()
                }
            } finally { runtime.close() }
        }
    }
    private class Wire : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<JsonObject>(); private val buffer = StringBuilder()
        override fun onOpen(socket: WebSocket) { socket.request(1) }
        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data); if (last) { frames.add(Json.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }
            socket.request(1); return CompletableFuture.completedFuture(null)
        }
        fun next(): JsonObject = assertNotNull(frames.poll(5, TimeUnit.SECONDS), "Expected native booking WS frame")
        fun silentThrough(id: UUID) {
            val frame = next(); assertEquals(id.toString(), frame["eventId"]?.jsonPrimitive?.content)
            assertEquals("notification.created", frame["type"]?.jsonPrimitive?.content)
            assertNull(frames.poll(250, TimeUnit.MILLISECONDS), "Unexpected frame after ordered USER marker")
        }
    }
    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}