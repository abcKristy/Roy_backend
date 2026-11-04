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
import kotlin.test.*

class BookingTransactionIntegrationTest {
    @Test
    fun `deferred commit failure rolls back booking and same key retry commits one snapshot`() = runBlocking<Unit> {
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
                fun request(proposal: UUID, body: JsonObject?, key: UUID?, expected: Int): JsonObject {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/$proposal${if (body == null) "" else "/booking"}"))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", f.bearer(f.booker))
                    val command = if (body == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                        .header("Idempotency-Key", checkNotNull(key).toString()).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
                    val response = client.send(command, HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, response.statusCode())
                    return Json.parseToJsonElement(response.body()).jsonObject
                }
                val wires = f.people.associateWith { actor ->
                    val wire = Wire()
                    val socket = client.newWebSocketBuilder().header("Authorization", f.bearer(actor))
                        .buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"), wire).get(5, TimeUnit.SECONDS)
                    sockets += socket
                    assertEquals("hello", wire.next()["type"]?.jsonPrimitive?.content)
                    val id = UUID.randomUUID()
                    socket.sendText(buildJsonObject {
                        put("v", 1); put("type", "subscribe"); put("requestId", id.toString())
                        put("chatIds", JsonArray(listOf(JsonPrimitive(f.chat.toString()))))
                    }.toString(), true).get(5, TimeUnit.SECONDS)
                    val reply = wire.next()
                    assertEquals(id.toString(), reply["requestId"]?.jsonPrimitive?.content)
                    assertEquals(listOf(JsonPrimitive(f.chat.toString())), reply["acceptedChatIds"]!!.jsonArray.toList())
                    wire
                }
                suspend fun silence() {
                    for ((actor, wire) in wires) {
                        val id = UUID.randomUUID()
                        hub.publishCommitted(RealtimeEvent(id, RealtimeEventType.NOTIFICATION_CREATED,
                            RealtimeScopeKind.USER, actor, Instant.now(), buildJsonObject { put("marker", id.toString()) }))
                        wire.silentThrough(id)
                    }
                }
                val key = UUID.randomUUID()
                val body = buildJsonObject { put("confirmOverlap", false); put("expectedRevision", 2) }
                val before = f.state()
                val ready = request(f.proposal, null, null, 200)
                assertEquals("ready_to_book", ready["status"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, ready["booking"])
                f.installDeferredFault()
                try {
                    f.assertDeferredFlags()
                    val failure = request(f.proposal, body, key, 500)
                    assertEquals("INTERNAL_ERROR", failure["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertEquals(before, f.state(), "Failed COMMIT must restore every business row and timestamp")
                    assertEquals(ready, request(f.proposal, null, null, 200))
                    silence()
                } finally { f.dropFault() }
                // The failed insertion may consume an identity value; gaps are valid and never reset.
                val afterFailedSequence = f.identityState()
                val created = request(f.proposal, body, key, 201)
                val booking = created["booking"]!!.jsonObject
                val bookingId = UUID.fromString(booking["id"]!!.jsonPrimitive.content)
                assertEquals("booked", created["status"]!!.jsonPrimitive.content)
                assertEquals(3L, created["revision"]!!.jsonPrimitive.long)
                assertEquals(f.booker.toString(), booking["bookedByUserId"]!!.jsonPrimitive.content)
                assertEquals("active", booking["status"]!!.jsonPrimitive.content)
                assertTrue(booking["bookingOrder"]!!.jsonPrimitive.long > 0)
                assertEquals(created, request(f.proposal, null, null, 200))
                f.assertCommitted(key, bookingId, created)
                var shared: Map<String, JsonObject>? = null
                for ((_, wire) in wires) {
                    val frames = List(3) { wire.next() }
                    assertEquals(setOf("chat.message.created", "proposal.updated", "booking.changed"), frames.map { it["type"]!!.jsonPrimitive.content }.toSet())
                    val byId = frames.associateBy { it["eventId"]!!.jsonPrimitive.content }
                    assertEquals(3, byId.size)
                    if (shared == null) shared = byId else assertEquals(shared, byId)
                    for (event in frames) {
                        assertEquals(buildJsonObject { put("kind", "chat"); put("id", f.chat.toString()) }, event["scope"])
                        assertFalse(event.toString().contains("viewerGoing"))
                        val payload = event["payload"]!!.jsonObject
                        if (event["type"]!!.jsonPrimitive.content == "chat.message.created") {
                            assertEquals("system", payload["type"]!!.jsonPrimitive.content)
                            assertEquals("meeting_booked", payload["system"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
                            assertEquals(bookingId.toString(), payload["system"]!!.jsonObject["bookingId"]!!.jsonPrimitive.content)
                        } else assertEquals(bookingId.toString(), payload["bookingId"]!!.jsonPrimitive.content)
                    }
                }
                val committed = f.state()
                val sequenceBeforeReplay = f.identityState()
                assertTrue(sequenceBeforeReplay.first >= afterFailedSequence.first)
                assertEquals(created, request(f.proposal, body, key, 201))
                // Revision and confirmation are excluded from the booking operation hash.
                val alteredReplay = buildJsonObject { put("confirmOverlap", true); put("expectedRevision", Long.MAX_VALUE) }
                assertEquals(created, request(f.proposal, alteredReplay, key, 201))
                assertEquals(sequenceBeforeReplay, f.identityState(), "Replay must not allocate another booking order")
                assertEquals(committed, f.state())
                silence()
                val reusedPath = request(f.otherProposal, body, key, 409)
                assertEquals("IDEMPOTENCY_KEY_REUSED", reusedPath["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertEquals(committed, f.state())
                assertEquals(sequenceBeforeReplay, f.identityState())
                silence()
            } finally { sockets.forEach { it.abort() }; server.stop(0, 1000) }
        }
    }

    private data class State(val bookings: List<List<String?>>, val votes: List<List<String?>>, val proposals: List<List<String?>>,
        val point: List<List<String?>>, val messages: List<List<String?>>, val chat: List<List<String?>>,
        val notifications: List<List<String?>>, val jobs: List<List<String?>>, val outcomes: List<List<String?>>)
    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_booking_transaction$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-booking-transaction-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val proposer = UUID.randomUUID(); val booker = UUID.randomUUID(); val people = listOf(proposer, booker)
        private val sessions = people.associateWith { UUID.randomUUID() }
        private val point = UUID.randomUUID(); val chat = UUID.randomUUID()
        val proposal = UUID.randomUUID(); val otherProposal = UUID.randomUUID()
        private val faultName = "m4_booking_commit_fault_" + booker.toString().replace("-", "")
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Commit fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    // Opaque synthetic device data; no delivery worker or external provider is started.
                    val installation = UUID.randomUUID()
                    execute(c, "INSERT INTO device_registrations(installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at) VALUES (?,?,'IOS_APNS','synthetic-never-delivered-ciphertext',?,clock_timestamp(),clock_timestamp())",
                        installation, proposer, installation.toString().replace("-", "").repeat(2))
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic commit fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, proposer)
                    val topic = UUID.randomUUID()
                    execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in people) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    for ((index, id) in listOf(proposal, otherProposal).withIndex()) {
                        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                            VALUES (?,?,?,clock_timestamp()+?::interval,clock_timestamp()+?::interval,2,clock_timestamp(),clock_timestamp())""",
                            id, chat, proposer, "${24 + index * 2} hours", "${25 + index * 2} hours")
                        for (voter in people) execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", id, voter)
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
        fun state(): State = connection().use { c ->
            fun rows(sql: String, id: UUID = chat): List<List<String?>> = c.prepareStatement(sql).use { s ->
                s.setObject(1, id); s.executeQuery().use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
            }
            State(rows("SELECT b.id,b.proposal_id,b.booking_order,b.booked_by_user_id,b.status,b.created_at,b.cancelled_at,b.cancel_reason FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id=? ORDER BY b.id"),
                rows("SELECT v.proposal_id,v.user_id,v.created_at FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=? ORDER BY v.proposal_id,v.user_id"),
                rows("SELECT id,revision,lifecycle,closed_reason,updated_at FROM meeting_proposals WHERE chat_id=? ORDER BY id"),
                rows("SELECT updated_at FROM points WHERE id=?", point),
                rows("SELECT id,message_seq,type,system_type,system_payload,created_at FROM messages WHERE chat_id=? ORDER BY message_seq"),
                rows("SELECT last_message_seq FROM chats WHERE id=?"),
                rows("SELECT id,recipient_user_id,event_type,dedupe_key,booking_id,display_snapshot FROM notification_events WHERE chat_id=? ORDER BY id"),
                rows("SELECT j.id,j.notification_event_id,j.installation_id,j.status,j.attempts,j.next_attempt_at FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.chat_id=? ORDER BY j.id"),
                rows("SELECT operation_type,idempotency_key,outcome,result_ref,response_snapshot,created_at FROM operation_idempotency WHERE actor_user_id=? ORDER BY operation_type,idempotency_key", booker))
        }
        fun identityState(): Pair<Long, Boolean> = connection().use { c ->
            val sequence = c.createStatement().use { s -> s.executeQuery("SELECT pg_get_serial_sequence('bookings','booking_order')").use { r -> check(r.next()); r.getString(1) } }
            require(sequence.startsWith("public.") && Regex("[a-z_]+").matches(sequence.removePrefix("public.")))
            c.createStatement().use { s -> s.executeQuery("SELECT last_value,is_called FROM $sequence").use { r -> check(r.next()); r.getLong(1) to r.getBoolean(2) } }
        }
        fun installDeferredFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE FUNCTION $faultName() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic deferred booking COMMIT fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE CONSTRAINT TRIGGER $faultName AFTER INSERT ON operation_idempotency DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.actor_user_id='$booker'::uuid AND NEW.operation_type='book_meeting') EXECUTE FUNCTION $faultName()")
        } }
        fun assertDeferredFlags() = connection().use { c ->
            c.prepareStatement("SELECT tgdeferrable,tginitdeferred FROM pg_trigger WHERE tgname=? AND tgrelid='operation_idempotency'::regclass").use { s ->
                s.setString(1, faultName); s.executeQuery().use { r -> assertTrue(r.next()); assertTrue(r.getBoolean(1)); assertTrue(r.getBoolean(2)); assertFalse(r.next()) }
            }
        }
        fun dropFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("DROP TRIGGER IF EXISTS $faultName ON operation_idempotency"); s.execute("DROP FUNCTION IF EXISTS $faultName()")
        } }
        fun assertCommitted(key: UUID, bookingId: UUID, created: JsonObject) = connection().use { c ->
            c.prepareStatement("SELECT b.booking_order,b.booked_by_user_id,b.status,p.revision FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE b.id=? AND p.id=?").use { s ->
                s.setObject(1, bookingId); s.setObject(2, proposal)
                s.executeQuery().use { r ->
                    assertTrue(r.next()); assertEquals(created["booking"]!!.jsonObject["bookingOrder"]!!.jsonPrimitive.long, r.getLong(1))
                    assertEquals(booker, r.getObject(2, UUID::class.java)); assertEquals("ACTIVE", r.getString(3)); assertEquals(3L, r.getLong(4))
                }
            }
            c.prepareStatement("SELECT outcome,result_ref,response_snapshot FROM operation_idempotency WHERE actor_user_id=? AND operation_type='book_meeting' AND idempotency_key=?").use { s ->
                s.setObject(1, booker); s.setString(2, key.toString())
                s.executeQuery().use { r ->
                    assertTrue(r.next()); assertEquals("SUCCESS", r.getString(1)); assertEquals(bookingId, r.getObject(2, UUID::class.java))
                    assertEquals(buildJsonObject { put("status", 201); put("body", created) }, Json.parseToJsonElement(r.getString(3)))
                }
            }
            c.prepareStatement("SELECT system_type,system_payload->>'bookingId' FROM messages WHERE chat_id=?").use { s ->
                s.setObject(1, chat); s.executeQuery().use { r -> assertTrue(r.next()); assertEquals("meeting_booked", r.getString(1)); assertEquals(bookingId.toString(), r.getString(2)); assertFalse(r.next()) }
            }
            c.prepareStatement("SELECT count(*),(SELECT count(*) FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.booking_id=?) FROM notification_events WHERE booking_id=?").use { s ->
                s.setObject(1, bookingId); s.setObject(2, bookingId)
                s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(1, r.getInt(1)); assertEquals(1, r.getInt(2)) }
            }
        }
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
        fun silentThrough(id: UUID) { assertEquals(id.toString(), next()["eventId"]?.jsonPrimitive?.content); assertNull(frames.poll(250, TimeUnit.MILLISECONDS)) }
    }
    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}
