package com.example.meetings

import com.example.accounts.AccountDeletionService
import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.points.PointWriteService
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
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class BookingCancellationEventsIntegrationTest {
    @Test fun `unvote cancellation emits one committed reasoned event set`() = runBlocking<Unit> { exercise(Path.UNVOTE) }
    @Test fun `leave cancellation reaches remaining members only after commit`() = runBlocking<Unit> { exercise(Path.LEAVE) }
    @Test fun `account deletion cancellation anonymizes actor and closes departed session`() = runBlocking<Unit> { exercise(Path.DELETE_ACCOUNT) }
    private enum class Path { UNVOTE, LEAVE, DELETE_ACCOUNT }

    private suspend fun exercise(path: Path) {
        Fixture().use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val hub = RealtimeHub(f.runtime, access)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport(); installRealtimeTransport()
                configureRouting(f.runtime, sessionAccess = access, realtimeHub = hub,
                    proposalService = ProposalService(f.runtime, f.secret), bookingService = BookingService(f.runtime),
                    pointWriteService = PointWriteService(f.runtime),
                    accountDeletionService = AccountDeletionService(f.runtime, f.passwordHashing))
            }
            val sockets = mutableListOf<WebSocket>()
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                fun action(): HttpRequest {
                    val endpoint = when (path) {
                        Path.UNVOTE -> "/api/v1/proposals/${f.proposal}/vote"
                        Path.LEAVE -> "/api/v1/chats/${f.chat}/membership"
                        Path.DELETE_ACCOUNT -> "/api/v1/me/deletion"
                    }
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$endpoint"))
                        .timeout(Duration.ofSeconds(40)).header("Authorization", f.bearer(f.actor))
                    return when (path) {
                        Path.UNVOTE -> builder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("""{"going":false}""")).build()
                        Path.LEAVE -> builder.DELETE().build()
                        Path.DELETE_ACCOUNT -> builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(buildJsonObject {
                            put("currentPassword", f.password); put("confirm", true)
                        }.toString())).build()
                    }
                }
                fun sendAction(expected: Int): HttpResponse<String> = client.send(action(), HttpResponse.BodyHandlers.ofString()).also { assertEquals(expected, it.statusCode()) }
                fun get(actor: UUID, expected: Int = 200): JsonObject {
                    val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/${f.proposal}"))
                        .timeout(Duration.ofSeconds(15)).header("Authorization", f.bearer(actor)).GET().build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, response.statusCode()); return Json.parseToJsonElement(response.body()).jsonObject
                }
                fun connect(actor: UUID, chat: UUID, accept: Boolean = true): Wire {
                    val wire = Wire()
                    val socket = client.newWebSocketBuilder().header("Authorization", f.bearer(actor))
                        .buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"), wire).get(5, TimeUnit.SECONDS)
                    sockets += socket; assertEquals("hello", wire.next()["type"]?.jsonPrimitive?.content)
                    val id = UUID.randomUUID()
                    socket.sendText(buildJsonObject { put("v", 1); put("type", "subscribe"); put("requestId", id.toString()); put("chatIds", JsonArray(listOf(JsonPrimitive(chat.toString())))) }.toString(), true).get(5, TimeUnit.SECONDS)
                    val response = wire.next(); assertEquals(id.toString(), response["requestId"]?.jsonPrimitive?.content)
                    assertEquals(listOf(JsonPrimitive(chat.toString())), response[if (accept) "acceptedChatIds" else "rejectedChatIds"]!!.jsonArray.toList())
                    return wire
                }
                val remaining = linkedMapOf(f.owner to connect(f.owner, f.chat), f.observer to connect(f.observer, f.chat))
                val departing = connect(f.actor, f.chat)
                val adjacent = connect(f.observer, f.adjacentChat)
                val outsider = connect(f.outsider, f.chat, false)
                suspend fun marker(actor: UUID, wires: List<Wire>): List<List<JsonObject>> {
                    val id = UUID.randomUUID()
                    hub.publishCommitted(RealtimeEvent(id, RealtimeEventType.NOTIFICATION_CREATED, RealtimeScopeKind.USER, actor, Instant.now(), buildJsonObject { put("marker", id.toString()) }))
                    return wires.map { it.through(id) }
                }
                suspend fun silenceBeforeCommit() {
                    assertTrue(marker(f.owner, listOf(remaining.getValue(f.owner))).single().isEmpty())
                    marker(f.observer, listOf(remaining.getValue(f.observer), adjacent)).forEach { assertTrue(it.isEmpty()) }
                    assertTrue(marker(f.actor, listOf(departing)).single().isEmpty())
                    assertTrue(marker(f.outsider, listOf(outsider)).single().isEmpty())
                }
                val baseline = f.state()
                val initial = get(f.owner)
                assertEquals("booked", initial["status"]!!.jsonPrimitive.content)
                assertEquals("active", initial["booking"]!!.jsonObject["status"]!!.jsonPrimitive.content)
                f.installDeferredFault()
                try {
                    val failure = sendAction(500)
                    assertEquals("INTERNAL_ERROR", Json.parseToJsonElement(failure.body()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertEquals(baseline, f.state()); assertEquals(initial, get(f.owner)); silenceBeforeCommit()
                } finally { f.dropFault() }

                val lower = f.databaseNow()
                lateinit var pending: CompletableFuture<HttpResponse<String>>
                f.connection().use { gate ->
                    gate.autoCommit = false; gate.createStatement().use { it.execute("LOCK TABLE messages IN SHARE MODE") }
                    pending = client.sendAsync(action(), HttpResponse.BodyHandlers.ofString())
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (!blocked && System.nanoTime() < deadline) {
                        blocked = f.connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation='messages'::regclass AND mode='RowExclusiveLock' AND NOT granted)").use { r -> check(r.next()); r.getBoolean(1) } } }
                        if (!blocked) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
                    }
                    assertTrue(blocked); assertFalse(pending.isDone)
                    assertEquals(baseline, f.state()); assertEquals(initial, get(f.owner)); silenceBeforeCommit()
                    assertFalse(pending.isDone); gate.commit()
                }
                val success = pending.get(15, TimeUnit.SECONDS)
                assertEquals(if (path == Path.UNVOTE) 200 else 204, success.statusCode())
                val upper = f.databaseNow()
                val cancelled = get(f.owner)
                assertEquals("voting", cancelled["status"]!!.jsonPrimitive.content)
                assertEquals(1, cancelled["goingCount"]!!.jsonPrimitive.int); assertEquals(4L, cancelled["revision"]!!.jsonPrimitive.long)
                val booking = cancelled["booking"]!!.jsonObject
                assertEquals(f.booking.toString(), booking["id"]!!.jsonPrimitive.content)
                assertEquals("cancelled", booking["status"]!!.jsonPrimitive.content)
                assertEquals("insufficient_votes", booking["cancelReason"]!!.jsonPrimitive.content)
                val cancelledAt = Instant.parse(booking["cancelledAt"]!!.jsonPrimitive.content)
                assertTrue(!cancelledAt.isBefore(lower) && !cancelledAt.isAfter(upper))
                assertEquals(if (path == Path.DELETE_ACCOUNT) JsonNull else JsonPrimitive(f.actor.toString()), booking["bookedByUserId"])
                val ownerFrames = marker(f.owner, listOf(remaining.getValue(f.owner))).single()
                val observerFrames = marker(f.observer, listOf(remaining.getValue(f.observer), adjacent))
                fun cancellationFrames(frames: List<JsonObject>): Map<String, JsonObject> {
                    val relevant = frames.filter { event -> when (event["type"]?.jsonPrimitive?.content) {
                        "proposal.updated", "booking.changed" -> true
                        "chat.message.created" -> event["payload"]!!.jsonObject["system"]?.jsonObject?.get("kind")?.jsonPrimitive?.content == "meeting_booking_cancelled"
                        else -> false
                    } }
                    assertEquals(3, relevant.size)
                    assertEquals(setOf("chat.message.created", "proposal.updated", "booking.changed"), relevant.map { it["type"]!!.jsonPrimitive.content }.toSet())
                    for (event in relevant) {
                        assertEquals(setOf("v", "eventId", "type", "scope", "occurredAt", "payload"), event.keys)
                        assertEquals(1, event["v"]!!.jsonPrimitive.int); UUID.fromString(event["eventId"]!!.jsonPrimitive.content)
                        assertEquals(buildJsonObject { put("kind", "chat"); put("id", f.chat.toString()) }, event["scope"])
                        assertFalse(event.toString().contains("viewerGoing")); assertNotEquals("booking.cancelled", event["type"]!!.jsonPrimitive.content)
                        val payload = event["payload"]!!.jsonObject
                        when (event["type"]!!.jsonPrimitive.content) {
                            "chat.message.created" -> {
                                assertEquals("system", payload["type"]!!.jsonPrimitive.content)
                                assertEquals(buildJsonObject {
                                    put("kind", "meeting_booking_cancelled"); put("actorUserId", if (path == Path.DELETE_ACCOUNT) JsonNull else JsonPrimitive(f.actor.toString()))
                                    put("pointId", f.point.toString()); put("chatId", f.chat.toString()); put("proposalId", f.proposal.toString()); put("bookingId", f.booking.toString())
                                    put("startsAt", JsonNull); put("endsAt", JsonNull); put("reason", "insufficient_votes")
                                }, payload["system"])
                                val createdAt = Instant.parse(payload["createdAt"]!!.jsonPrimitive.content)
                                assertTrue(!createdAt.isBefore(cancelledAt) && !createdAt.isAfter(upper))
                                assertEquals(payload["createdAt"], event["occurredAt"]); f.assertStoredMessage(payload)
                            }
                            "proposal.updated" -> assertEquals(buildJsonObject {
                                put("proposalId", f.proposal.toString()); put("revision", 4); put("status", "voting"); put("goingCount", 1); put("bookingId", f.booking.toString())
                            }, payload)
                            "booking.changed" -> assertEquals(buildJsonObject {
                                put("proposalId", f.proposal.toString()); put("bookingId", f.booking.toString()); put("status", "cancelled"); put("cancelReason", "insufficient_votes")
                            }, payload)
                        }
                    }
                    return relevant.associateBy { it["eventId"]!!.jsonPrimitive.content }
                }
                val canonicalEvents = cancellationFrames(ownerFrames)
                assertEquals(canonicalEvents, cancellationFrames(observerFrames[0]))
                fun noPrivate(frames: List<JsonObject>) {
                    for (event in frames) {
                        assertFalse(event.toString().contains(f.booking.toString())); assertFalse(event.toString().contains(f.proposal.toString()))
                        assertTrue(event["type"]!!.jsonPrimitive.content in setOf("point.changed", "chat.membership.changed", "session.revoked"))
                    }
                }
                noPrivate(observerFrames[1]); assertTrue(marker(f.outsider, listOf(outsider)).single().isEmpty())
                if (path == Path.UNVOTE) assertEquals(canonicalEvents, cancellationFrames(marker(f.actor, listOf(departing)).single()))
                else if (path == Path.LEAVE) {
                    noPrivate(marker(f.actor, listOf(departing)).single())
                    assertFalse(get(f.actor, 403).toString().contains(f.booking.toString()))
                } else {
                    assertEquals(4401, departing.closed.get(5, TimeUnit.SECONDS)); noPrivate(departing.drain())
                    assertFalse(get(f.actor, 401).toString().contains(f.booking.toString()))
                }
                f.assertCancellation()
                val stable = f.state()
                sendAction(if (path == Path.UNVOTE) 200 else if (path == Path.LEAVE) 204 else 401)
                assertEquals(cancelled, get(f.owner)); get(f.observer); f.assertCancellation(); assertEquals(stable, f.state())
                assertTrue(marker(f.owner, listOf(remaining.getValue(f.owner))).single().isEmpty())
                marker(f.observer, listOf(remaining.getValue(f.observer), adjacent)).forEach { assertTrue(it.isEmpty()) }
                assertTrue(marker(f.outsider, listOf(outsider)).single().isEmpty())
                if (path != Path.DELETE_ACCOUNT) assertTrue(marker(f.actor, listOf(departing)).single().isEmpty())
            } finally { sockets.forEach { it.abort() }; server.stop(0, 1000) }
        }
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_cancel_events$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-cancel-events-test-secret-at-least-32-bytes"
        private val hasher = Argon2idPasswordHasher(); val passwordHashing = PasswordHashingWork(hasher)
        val password = "SyntheticCancelPassword42!"
        private val encodedPassword = hasher.hash(password)
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, dbPassword, secret, appEnvironment = AppEnvironment.LOCAL,
            databaseMaxPoolSize = 8, databaseLockTimeoutMs = 30_000, databaseStatementTimeoutMs = 35_000)).start()
        val owner = UUID.randomUUID(); val actor = UUID.randomUUID(); val observer = UUID.randomUUID(); val outsider = UUID.randomUUID()
        private val people = listOf(owner, actor, observer, outsider); private val sessions = people.associateWith { UUID.randomUUID() }
        val point = UUID.randomUUID(); val chat = UUID.randomUUID(); val adjacentChat = UUID.randomUUID()
        val proposal = UUID.randomUUID(); val booking = UUID.randomUUID()
        private val faultName = "m4_cancel_event_fault_" + booking.toString().replace("-", "")
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,?,'Cancel fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone, encodedPassword)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic cancellation fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, owner)
                    for ((index, id) in listOf(chat, adjacentChat).withIndex()) {
                        val topic = UUID.randomUUID(); execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())", topic, point, if (index == 0) "football" else "basketball")
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", id, topic)
                        for (member in if (id == chat) listOf(owner, actor, observer) else listOf(observer)) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", id, member)
                    }
                    execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                        VALUES (?,?,?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '25 hours',3,clock_timestamp(),clock_timestamp())""", proposal, chat, owner)
                    for (id in listOf(owner, actor)) execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", proposal, id)
                    execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())", booking, proposal, actor)
                    c.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }
        fun connection(): Connection = DriverManager.getConnection(url, user, dbPassword)
        fun databaseNow(): Instant = connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant() } } }
        fun bearer(id: UUID): String { val now = Instant.now(); return "Bearer " + JwtTokenIssuer(secret).issue(id, sessions.getValue(id), 0, now.minusSeconds(1), now.plusSeconds(3600)) }
        fun state(): List<List<String?>> = connection().use { c ->
            val fragments = listOf(
                "SELECT b.id::text,b.status,b.cancelled_at::text,b.cancel_reason FROM bookings b WHERE b.id=?" to listOf(booking),
                "SELECT proposal_id::text,user_id::text,created_at::text,'' FROM votes WHERE proposal_id=?" to listOf(proposal),
                "SELECT id::text,revision::text,updated_at::text,lifecycle FROM meeting_proposals WHERE id=?" to listOf(proposal),
                "SELECT chat_id::text,user_id::text,left_at::text,joined_at::text FROM chat_memberships WHERE chat_id IN (?,?)" to listOf(chat, adjacentChat),
                "SELECT id::text,auth_version::text,deleted_at::text,display_name FROM users WHERE id IN (?,?,?,?)" to people,
                "SELECT id::text,user_id::text,revoked_at::text,expires_at::text FROM auth_sessions WHERE user_id IN (?,?,?,?)" to people,
                "SELECT id::text,message_seq::text,system_type,system_payload::text FROM messages WHERE chat_id=?" to listOf(chat),
                "SELECT id::text,recipient_user_id::text,event_type,dedupe_key FROM notification_events WHERE point_id=?" to listOf(point),
                "SELECT id::text,last_message_seq::text,'','' FROM chats WHERE id=?" to listOf(chat),
                "SELECT id::text,updated_at::text,'','' FROM points WHERE id=?" to listOf(point))
            c.prepareStatement(fragments.joinToString(" UNION ALL ") { it.first } + " ORDER BY 1,2,3,4").use { s ->
                fragments.flatMap { it.second }.forEachIndexed { index, id -> s.setObject(index + 1, id) }
                s.executeQuery().use { r -> buildList { while (r.next()) add((1..4).map { r.getString(it) }) } }
            }
        }
        fun installDeferredFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE FUNCTION $faultName() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic cancellation event COMMIT fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE CONSTRAINT TRIGGER $faultName AFTER UPDATE ON bookings DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (OLD.status='ACTIVE' AND NEW.status='CANCELLED' AND NEW.id='$booking'::uuid) EXECUTE FUNCTION $faultName()")
        } }
        fun dropFault() = connection().use { c -> c.createStatement().use { s -> s.execute("DROP TRIGGER IF EXISTS $faultName ON bookings"); s.execute("DROP FUNCTION IF EXISTS $faultName()") } }
        fun assertStoredMessage(message: JsonObject) = connection().use { c -> c.prepareStatement("SELECT message_seq,system_payload FROM messages WHERE id=? AND chat_id=?").use { s ->
            s.setObject(1, UUID.fromString(message["id"]!!.jsonPrimitive.content)); s.setObject(2, chat)
            s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(message["messageSeq"]!!.jsonPrimitive.long, r.getLong(1)); assertEquals(message["system"], Json.parseToJsonElement(r.getString(2))) }
        } }
        fun assertCancellation() = connection().use { c ->
            c.prepareStatement("SELECT count(*) FROM messages WHERE chat_id=? AND system_type='meeting_booking_cancelled' AND system_payload->>'bookingId'=?").use { s ->
                s.setObject(1, chat); s.setString(2, booking.toString()); s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(1, r.getInt(1)) }
            }
            c.prepareStatement("SELECT count(*),min(dedupe_key),max(dedupe_key) FROM notification_events WHERE booking_id=? AND event_type='booking_cancelled'").use { s ->
                s.setObject(1, booking); s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(2, r.getInt(1)); assertEquals("booking_cancelled:$booking", r.getString(2)); assertEquals(r.getString(2), r.getString(3)) }
            }
        }
        override fun close() {
            try { dropFault(); connection().use { c -> c.autoCommit = false; execute(c, "DELETE FROM points WHERE id=?", point); for (id in people) execute(c, "DELETE FROM users WHERE id=?", id); c.commit() } }
            finally { runtime.close() }
        }
    }
    private class Wire : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<JsonObject>(); private val buffer = StringBuilder(); val closed = CompletableFuture<Int>()
        override fun onOpen(socket: WebSocket) { socket.request(1) }
        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> { buffer.append(data); if (last) { frames.add(Json.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }; socket.request(1); return CompletableFuture.completedFuture(null) }
        override fun onClose(socket: WebSocket, code: Int, reason: String): CompletionStage<*> { closed.complete(code); return CompletableFuture.completedFuture(null) }
        override fun onError(socket: WebSocket, error: Throwable) { closed.completeExceptionally(error) }
        fun next(): JsonObject = assertNotNull(frames.poll(5, TimeUnit.SECONDS), "Expected cancellation WS frame")
        fun through(id: UUID): List<JsonObject> {
            val result = mutableListOf<JsonObject>(); val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) { assertTrue(System.nanoTime() < deadline); val frame = next(); if (frame["eventId"]?.jsonPrimitive?.content == id.toString()) break; result += frame }
            assertNull(frames.poll(250, TimeUnit.MILLISECONDS)); return result
        }
        fun drain(): List<JsonObject> = buildList { while (true) add(frames.poll() ?: break) }
    }
    companion object { private fun execute(c: Connection, sql: String, vararg values: Any) { c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() } } }
}