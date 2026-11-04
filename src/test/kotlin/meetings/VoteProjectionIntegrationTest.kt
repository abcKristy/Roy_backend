package com.example.meetings

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.api.proposalJson
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

class VoteProjectionIntegrationTest {
    @Test
    fun `committed counts and status are shared while REST going flags remain personal`() = runBlocking<Unit> {
        Fixture().use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val hub = RealtimeHub(f.runtime, access)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport(); installRealtimeTransport()
                configureRouting(f.runtime, sessionAccess = access, realtimeHub = hub,
                    proposalService = f.proposals, bookingService = BookingService(f.runtime))
            }
            val sockets = mutableListOf<WebSocket>()
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                fun request(actor: UUID, path: String, going: Boolean? = null): HttpRequest {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .timeout(Duration.ofSeconds(45)).header("Authorization", f.bearer(actor))
                    return if (going == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""{"going":$going}""")).build()
                }
                fun parse(response: HttpResponse<String>): JsonObject {
                    assertEquals(200, response.statusCode())
                    return Json.parseToJsonElement(response.body()).jsonObject
                }
                fun get(actor: UUID, path: String) = parse(client.send(request(actor, path), HttpResponse.BodyHandlers.ofString()))
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
                        val marker = UUID.randomUUID()
                        hub.publishCommitted(RealtimeEvent(marker, RealtimeEventType.NOTIFICATION_CREATED,
                            RealtimeScopeKind.USER, actor, Instant.now(), buildJsonObject { put("marker", marker.toString()) }))
                        wire.silentThrough(marker)
                    }
                }
                suspend fun snapshots(count: Int, revision: Long, going: Set<UUID>) {
                    val before = f.state()
                    assertEquals(count.toLong(), before.votes)
                    assertEquals(revision, before.revision)
                    for (actor in f.people) {
                        val detail = assertNotNull(f.proposals.detail(f.proposal, actor))
                        val list = assertNotNull(f.proposals.list(f.chat, actor)).items.single()
                        assertEquals(detail, list)
                        assertEquals(count, detail.goingCount)
                        assertEquals(revision, detail.revision)
                        assertEquals(actor in going, detail.viewerGoing)
                        assertEquals(if (count > 1) "ready_to_book" else "voting", detail.status)
                        assertNull(detail.booking)
                        val rest = get(actor, "/api/v1/proposals/${f.proposal}")
                        assertEquals(proposalJson(detail), rest)
                        assertEquals(actor in going, rest["viewerGoing"]!!.jsonPrimitive.boolean)
                        val restList = get(actor, "/api/v1/chats/${f.chat}/proposals")
                        assertEquals(listOf(rest), restList["items"]!!.jsonArray.toList())
                    }
                    assertEquals(before, f.state(), "Reading personal snapshots must not mutate votes or effects")
                }
                fun events(count: Int, revision: Long, readinessMessage: Boolean = false) {
                    var commonMessage: JsonObject? = null
                    var commonUpdate: JsonObject? = null
                    for ((_, wire) in wires) {
                        if (readinessMessage) {
                            val message = wire.next()
                            assertEquals("chat.message.created", message["type"]?.jsonPrimitive?.content)
                            val payload = message["payload"]!!.jsonObject
                            assertEquals("system", payload["type"]!!.jsonPrimitive.content)
                            assertEquals("meeting_ready", payload["system"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
                            assertEquals(f.proposal.toString(), payload["system"]!!.jsonObject["proposalId"]!!.jsonPrimitive.content)
                            if (commonMessage == null) commonMessage = message else assertEquals(commonMessage, message)
                        }
                        val event = wire.next()
                        assertEquals("proposal.updated", event["type"]?.jsonPrimitive?.content)
                        assertEquals(buildJsonObject { put("kind", "chat"); put("id", f.chat.toString()) }, event["scope"])
                        assertEquals(buildJsonObject {
                            put("proposalId", f.proposal.toString()); put("revision", revision)
                            put("status", if (count > 1) "ready_to_book" else "voting")
                            put("goingCount", count); put("bookingId", JsonNull)
                        }, event["payload"])
                        assertFalse(event.toString().contains("viewerGoing"))
                        UUID.fromString(event["eventId"]!!.jsonPrimitive.content)
                        if (commonUpdate == null) commonUpdate = event else assertEquals(commonUpdate, event)
                    }
                    assertEquals(revision, f.state().revision)
                }
                snapshots(0, 0, emptySet())
                lateinit var pending: CompletableFuture<HttpResponse<String>>
                f.connection().use { gate ->
                    gate.autoCommit = false
                    gate.createStatement().use { it.execute("LOCK TABLE notification_events IN SHARE MODE") }
                    pending = client.sendAsync(request(f.a, "/api/v1/proposals/${f.proposal}/vote", true), HttpResponse.BodyHandlers.ofString())
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (!blocked && System.nanoTime() < deadline) {
                        blocked = f.connection().use { c -> c.createStatement().use { s ->
                            s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation='notification_events'::regclass AND mode='RowExclusiveLock' AND NOT granted)").use { rows ->
                                check(rows.next()); rows.getBoolean(1)
                            }
                        } }
                        if (!blocked) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
                    }
                    assertTrue(blocked, "Vote must reach its blocked transactional notification INSERT")
                    assertFalse(pending.isDone)
                    snapshots(0, 0, emptySet())
                    silence()
                    assertFalse(pending.isDone, "Vote must remain pending until the gate is released")
                    gate.commit()
                }
                val first = parse(pending.get(15, TimeUnit.SECONDS))
                assertEquals(true, first["viewerGoing"]!!.jsonPrimitive.boolean)
                assertEquals(1, first["goingCount"]!!.jsonPrimitive.int)
                events(1, 1)
                snapshots(1, 1, setOf(f.a))
                silence()
                val second = parse(client.send(request(f.b, "/api/v1/proposals/${f.proposal}/vote", true), HttpResponse.BodyHandlers.ofString()))
                assertEquals(true, second["viewerGoing"]!!.jsonPrimitive.boolean)
                events(2, 2, readinessMessage = true)
                snapshots(2, 2, setOf(f.a, f.b))
                silence()
                val removed = parse(client.send(request(f.a, "/api/v1/proposals/${f.proposal}/vote", false), HttpResponse.BodyHandlers.ofString()))
                assertEquals(false, removed["viewerGoing"]!!.jsonPrimitive.boolean)
                events(1, 3)
                snapshots(1, 3, setOf(f.b))
                silence()
            } finally {
                sockets.forEach { it.abort() }
                server.stop(0, 1000)
            }
        }
    }

    private data class State(val votes: Long, val revision: Long, val messages: Long, val notifications: Long)

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_vote_projection$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-vote-projection-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL, databaseMaxPoolSize = 8,
            databaseLockTimeoutMs = 40_000, databaseStatementTimeoutMs = 50_000)).start()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val people = listOf(a, b, c)
        private val sessions = people.associateWith { UUID.randomUUID() }
        private val point = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val proposal = UUID.randomUUID()
        val proposals = ProposalService(runtime, secret)

        init {
            try {
                connection().use { conn ->
                    conn.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(conn, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Projection fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(conn, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(conn, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(conn, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic projection fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, a)
                    val topic = UUID.randomUUID()
                    execute(conn, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    execute(conn, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in people) execute(conn, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    execute(conn, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                        VALUES (?,?,?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '25 hours',clock_timestamp(),clock_timestamp())""", proposal, chat, a)
                    conn.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun bearer(actor: UUID): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, sessions.getValue(actor), 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun state(): State = connection().use { conn ->
            conn.prepareStatement("""SELECT (SELECT count(*) FROM votes WHERE proposal_id=?),revision,
                (SELECT count(*) FROM messages WHERE chat_id=?),(SELECT count(*) FROM notification_events WHERE proposal_id=?)
                FROM meeting_proposals WHERE id=?""").use { s ->
                s.setObject(1, proposal); s.setObject(2, chat); s.setObject(3, proposal); s.setObject(4, proposal)
                s.executeQuery().use { rows -> check(rows.next()); State(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4)) }
            }
        }
        override fun close() {
            try {
                connection().use { conn ->
                    conn.autoCommit = false
                    execute(conn, "DELETE FROM points WHERE id=?", point)
                    for (id in people) execute(conn, "DELETE FROM users WHERE id=?", id)
                    conn.commit()
                }
            } finally { runtime.close() }
        }
    }

    private class Wire : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<JsonObject>()
        private val buffer = StringBuilder()
        override fun onOpen(socket: WebSocket) { socket.request(1) }
        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data)
            if (last) { frames.add(Json.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }
            socket.request(1)
            return CompletableFuture.completedFuture(null)
        }
        fun next(): JsonObject = assertNotNull(frames.poll(5, TimeUnit.SECONDS), "Expected native WS frame")
        fun silentThrough(marker: UUID) {
            assertEquals(marker.toString(), next()["eventId"]?.jsonPrimitive?.content)
            assertNull(frames.poll(250, TimeUnit.MILLISECONDS), "Unexpected frame after ordered USER marker")
        }
    }

    companion object {
        private fun execute(conn: Connection, sql: String, vararg values: Any) {
            conn.prepareStatement(sql).use { s ->
                values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate()
            }
        }
    }
}
