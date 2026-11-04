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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class VoteReadinessIntegrationTest {
    @Test
    fun `readiness edges are atomic deduplicated and shared under retries and concurrent voters`() = runBlocking<Unit> {
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
                fun request(actor: UUID, proposal: UUID, going: Boolean? = null): JsonObject {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/$proposal${if (going == null) "" else "/vote"}"))
                        .timeout(Duration.ofSeconds(25)).header("Authorization", f.bearer(actor))
                    val command = if (going == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""{"going":$going}""")).build()
                    val response = client.send(command, HttpResponse.BodyHandlers.ofString())
                    return buildJsonObject {
                        put("status", response.statusCode()); put("body", Json.parseToJsonElement(response.body()))
                    }
                }
                fun vote(actor: UUID, proposal: UUID, going: Boolean, status: Int = 200): JsonObject {
                    val response = request(actor, proposal, going)
                    assertEquals(status, response["status"]!!.jsonPrimitive.int)
                    return response["body"]!!.jsonObject
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
                fun observe(proposal: UUID, updates: Map<Long, Int>, readyActors: Set<UUID> = emptySet()) {
                    val count = updates.size + if (readyActors.isEmpty()) 0 else 1
                    var common: Map<String, JsonObject>? = null
                    for ((_, wire) in wires) {
                        val frames = List(count) { wire.next() }
                        val byId = frames.associateBy { it["eventId"]!!.jsonPrimitive.content }
                        assertEquals(count, byId.size, "A socket must not receive duplicate event IDs")
                        if (common == null) common = byId else assertEquals(common, byId)
                        val updateFrames = frames.filter { it["type"]!!.jsonPrimitive.content == "proposal.updated" }
                        assertEquals(updates.keys, updateFrames.map { it["payload"]!!.jsonObject["revision"]!!.jsonPrimitive.long }.toSet())
                        for (event in frames) {
                            assertEquals(1, event["v"]!!.jsonPrimitive.int)
                            assertEquals(buildJsonObject { put("kind", "chat"); put("id", f.chat.toString()) }, event["scope"])
                            assertFalse(event.toString().contains("viewerGoing"))
                            UUID.fromString(event["eventId"]!!.jsonPrimitive.content)
                        }
                        for (event in updateFrames) {
                            val revision = event["payload"]!!.jsonObject["revision"]!!.jsonPrimitive.long
                            val goingCount = updates.getValue(revision)
                            assertEquals(buildJsonObject {
                                put("proposalId", proposal.toString()); put("revision", revision)
                                put("status", if (goingCount > 1) "ready_to_book" else "voting")
                                put("goingCount", goingCount); put("bookingId", JsonNull)
                            }, event["payload"])
                        }
                        val messages = frames.filter { it["type"]!!.jsonPrimitive.content == "chat.message.created" }
                        assertEquals(if (readyActors.isEmpty()) 0 else 1, messages.size)
                        for (event in messages) {
                            val message = event["payload"]!!.jsonObject
                            assertEquals(setOf("id", "chatId", "messageSeq", "type", "author", "text", "system", "createdAt"), message.keys)
                            assertEquals("system", message["type"]!!.jsonPrimitive.content)
                            assertEquals(JsonNull, message["author"]); assertEquals(JsonNull, message["text"])
                            val system = message["system"]!!.jsonObject
                            assertEquals(setOf("kind", "actorUserId", "pointId", "chatId", "proposalId", "bookingId", "startsAt", "endsAt", "reason"), system.keys)
                            assertEquals("meeting_ready", system["kind"]!!.jsonPrimitive.content)
                            assertEquals(proposal.toString(), system["proposalId"]!!.jsonPrimitive.content)
                            assertTrue(UUID.fromString(system["actorUserId"]!!.jsonPrimitive.content) in readyActors)
                            assertEquals(f.point.toString(), system["pointId"]!!.jsonPrimitive.content)
                            assertEquals(f.chat.toString(), system["chatId"]!!.jsonPrimitive.content)
                            assertEquals(JsonNull, system["bookingId"]); assertEquals(JsonNull, system["reason"])
                            assertEquals(message["createdAt"], event["occurredAt"])
                            f.assertStoredMessage(message)
                        }
                    }
                    assertEquals(updates.keys.max(), f.state(proposal).revision)
                }
                // One ready edge at 1->2; neither higher counts nor falling counts create another.
                vote(f.a, f.sequential, true); observe(f.sequential, mapOf(1L to 1))
                vote(f.b, f.sequential, true); observe(f.sequential, mapOf(2L to 2), setOf(f.b))
                f.assertReady(f.sequential, setOf(2L))
                vote(f.c, f.sequential, true); observe(f.sequential, mapOf(3L to 3))
                vote(f.c, f.sequential, false); observe(f.sequential, mapOf(4L to 2))
                vote(f.b, f.sequential, false); observe(f.sequential, mapOf(5L to 1))
                f.assertReady(f.sequential, setOf(2L))
                vote(f.b, f.sequential, true); observe(f.sequential, mapOf(6L to 2), setOf(f.b))
                f.assertReady(f.sequential, setOf(2L, 6L))
                val stable = f.state(f.sequential)
                repeat(2) {
                    assertEquals(200, request(f.c, f.sequential)["status"]!!.jsonPrimitive.int)
                    val repeated = vote(f.b, f.sequential, true)
                    assertEquals(6L, repeated["revision"]!!.jsonPrimitive.long)
                    vote(f.c, f.sequential, false)
                }
                assertEquals(stable, f.state(f.sequential))
                silence()

                // Distinct voters race from count1; post-commit updates may be delivered in either order.
                vote(f.a, f.racing, true); observe(f.racing, mapOf(1L to 1))
                val workers = Executors.newFixedThreadPool(2)
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                try {
                    val futures = listOf(f.b, f.c).map { actor -> workers.submit<JsonObject> {
                        ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                        vote(actor, f.racing, true)
                    } }
                    assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
                    val replies = futures.map { it.get(25, TimeUnit.SECONDS) }
                    assertEquals(setOf(2L, 3L), replies.map { it["revision"]!!.jsonPrimitive.long }.toSet())
                    assertEquals(setOf(2, 3), replies.map { it["goingCount"]!!.jsonPrimitive.int }.toSet())
                } finally {
                    start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
                }
                observe(f.racing, mapOf(2L to 2, 3L to 3), setOf(f.b, f.c))
                assertEquals(3L, f.state(f.racing).votes)
                f.assertReady(f.racing, setOf(2L))
                silence()

                // A failed notification INSERT occurs after the ready SYSTEM append in this transaction.
                vote(f.a, f.failing, true); observe(f.failing, mapOf(1L to 1))
                val beforeFault = f.state(f.failing)
                f.installFault()
                try {
                    val failure = vote(f.b, f.failing, true, 500)
                    assertEquals("INTERNAL_ERROR", failure["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertEquals(beforeFault, f.state(f.failing))
                    f.assertReady(f.failing, emptySet())
                    silence()
                } finally { f.dropFault() }
                vote(f.b, f.failing, true); observe(f.failing, mapOf(2L to 2), setOf(f.b))
                f.assertReady(f.failing, setOf(2L))
                val afterRetry = f.state(f.failing)
                vote(f.b, f.failing, true)
                assertEquals(afterRetry, f.state(f.failing))
                silence()
            } finally { sockets.forEach { it.abort() }; server.stop(0, 1000) }
        }
    }

    private data class State(val votes: Long, val revision: Long, val readyMessages: Long, val notifications: Long, val sequence: Long)

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_ready$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-vote-readiness-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL, databaseMaxPoolSize = 8,
            databaseLockTimeoutMs = 15_000, databaseStatementTimeoutMs = 20_000)).start()
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        val people = listOf(a, b, c)
        private val sessions = people.associateWith { UUID.randomUUID() }
        val point = UUID.randomUUID(); val chat = UUID.randomUUID()
        val sequential = UUID.randomUUID(); val racing = UUID.randomUUID(); val failing = UUID.randomUUID()
        private val faultName = "m4_ready_fault_" + failing.toString().replace("-", "")
        init {
            try {
                connection().use { conn ->
                    conn.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(conn, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Ready fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(conn, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(conn, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(conn, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic ready fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, a)
                    val topic = UUID.randomUUID()
                    execute(conn, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    execute(conn, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in people) execute(conn, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    for (id in listOf(sequential, racing, failing)) execute(conn,
                        """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                        VALUES (?,?,?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '25 hours',clock_timestamp(),clock_timestamp())""", id, chat, a)
                    conn.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }
        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun bearer(actor: UUID): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, sessions.getValue(actor), 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun state(proposal: UUID): State = connection().use { conn ->
            conn.prepareStatement("""SELECT (SELECT count(*) FROM votes WHERE proposal_id=?),revision,
                (SELECT count(*) FROM messages WHERE chat_id=? AND system_type='meeting_ready' AND system_payload->>'proposalId'=?),
                (SELECT count(*) FROM notification_events WHERE proposal_id=?),(SELECT last_message_seq FROM chats WHERE id=?)
                FROM meeting_proposals WHERE id=?""").use { s ->
                s.setObject(1, proposal); s.setObject(2, chat); s.setString(3, proposal.toString())
                s.setObject(4, proposal); s.setObject(5, chat); s.setObject(6, proposal)
                s.executeQuery().use { r -> check(r.next()); State(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5)) }
            }
        }
        fun assertReady(proposal: UUID, revisions: Set<Long>) {
            assertEquals(revisions.size.toLong(), state(proposal).readyMessages)
            connection().use { conn ->
                conn.prepareStatement("SELECT dedupe_key,count(*) FROM notification_events WHERE proposal_id=? AND event_type='proposal_ready' GROUP BY dedupe_key").use { s ->
                    s.setObject(1, proposal)
                    s.executeQuery().use { r ->
                        val actual = buildMap { while (r.next()) put(r.getString(1), r.getInt(2)) }
                        assertEquals(revisions.associate { "proposal_ready:$proposal:$it" to 2 }, actual)
                    }
                }
            }
        }
        fun assertStoredMessage(message: JsonObject) = connection().use { conn ->
            conn.prepareStatement("SELECT message_seq,system_type,system_payload FROM messages WHERE id=? AND chat_id=?").use { s ->
                s.setObject(1, UUID.fromString(message["id"]!!.jsonPrimitive.content)); s.setObject(2, chat)
                s.executeQuery().use { r ->
                    assertTrue(r.next()); assertEquals(message["messageSeq"]!!.jsonPrimitive.long, r.getLong(1))
                    assertEquals("meeting_ready", r.getString(2)); assertEquals(message["system"], Json.parseToJsonElement(r.getString(3)))
                }
            }
        }
        fun installFault() = connection().use { conn -> conn.createStatement().use { s ->
            s.execute("CREATE FUNCTION $faultName() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic readiness notification fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE TRIGGER $faultName BEFORE INSERT ON notification_events FOR EACH ROW WHEN (NEW.proposal_id='$failing'::uuid) EXECUTE FUNCTION $faultName()")
        } }
        fun dropFault() = connection().use { conn -> conn.createStatement().use { s ->
            s.execute("DROP TRIGGER IF EXISTS $faultName ON notification_events"); s.execute("DROP FUNCTION IF EXISTS $faultName()")
        } }
        override fun close() {
            try {
                dropFault()
                connection().use { conn ->
                    conn.autoCommit = false; execute(conn, "DELETE FROM points WHERE id=?", point)
                    for (id in people) execute(conn, "DELETE FROM users WHERE id=?", id)
                    conn.commit()
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
        fun next(): JsonObject = assertNotNull(frames.poll(5, TimeUnit.SECONDS), "Expected native readiness WS frame")
        fun silentThrough(id: UUID) {
            assertEquals(id.toString(), next()["eventId"]?.jsonPrimitive?.content)
            assertNull(frames.poll(250, TimeUnit.MILLISECONDS), "Unexpected event after ordered USER marker")
        }
    }
    companion object {
        private fun execute(conn: Connection, sql: String, vararg values: Any) {
            conn.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}
