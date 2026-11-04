package com.example.meetings

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.SessionAccessService
import com.example.jobs.ProposalExpiryScheduler
import com.example.persistence.JdbcSessionRepository
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class MeetingTimeBoundsIntegrationTest {
    @Test fun `vote true respects persisted microsecond start independently of expiry`() = runBlocking<Unit> { boundaries(Operation.VOTE_TRUE) }
    @Test fun `vote false cannot cancel a started active booking`() = runBlocking<Unit> { boundaries(Operation.VOTE_FALSE) }
    @Test fun `new booking before exact and after start differs from historical replay`() = runBlocking<Unit> { boundaries(Operation.BOOK) }
    @Test fun `author close is forbidden exactly at start and preserves booked history`() = runBlocking<Unit> { boundaries(Operation.CLOSE) }

    private enum class Operation { VOTE_TRUE, VOTE_FALSE, BOOK, CLOSE }
    private suspend fun boundaries(operation: Operation) {
        val ledger = mutableListOf<JsonObject>()
        for (offset in listOf(-1L, 0L, 1L)) MeetingAcceptanceFixture().use { f ->
            server(f, controlled = true) { wire ->
                val prepared = prepare(f, wire, operation)
                val id = prepared.id
                val start = persistedStart(f, id)
                assertEquals(0, start.nano % 1000)
                assertEquals(Instant.parse("2099-02-10T15:00:00.123456Z"), start)
                assertEquals(ZoneOffset.UTC, f.clock.zone)
                f.clock.set(start.plusNanos(offset * 1000))
                val body = when (operation) {
                    Operation.VOTE_TRUE -> buildJsonObject { put("going", true) }
                    Operation.VOTE_FALSE -> buildJsonObject { put("going", false) }
                    Operation.BOOK -> booking(prepared.revision)
                    Operation.CLOSE -> revision(prepared.revision)
                }
                val actor = if (operation == Operation.CLOSE) f.author else f.voter
                val method = if (operation == Operation.VOTE_TRUE || operation == Operation.VOTE_FALSE) "PUT" else "POST"
                val path = "/api/v1/proposals/$id/" + when (operation) { Operation.BOOK -> "booking"; Operation.CLOSE -> "close"; else -> "vote" }
                val key = if (method == "POST") UUID.randomUUID() else null
                val initial = proof(f, id)
                assertEquals("OPEN", initial["proposal"]!!.jsonObject["lifecycle"]!!.jsonPrimitive.content)
                val stable = f.state()
                val result = wire.send(actor, method, path, body, key)
                if (offset < 0) {
                    assertEquals(if (operation == Operation.BOOK) 201 else 200, result.status)
                    assertEquals(prepared.revision + 1, result.body["revision"]!!.jsonPrimitive.long)
                    when (operation) {
                        Operation.VOTE_TRUE -> assertEquals(2, result.body["goingCount"]!!.jsonPrimitive.int)
                        Operation.VOTE_FALSE -> { assertEquals(1, result.body["goingCount"]!!.jsonPrimitive.int); assertEquals("cancelled", result.body["booking"]!!.jsonObject["status"]!!.jsonPrimitive.content) }
                        Operation.BOOK -> assertEquals(start.minusNanos(1000), Instant.parse(result.body["booking"]!!.jsonObject["bookedAt"]!!.jsonPrimitive.content))
                        Operation.CLOSE -> { assertEquals("closed", result.body["status"]!!.jsonPrimitive.content); assertEquals("closed_by_author", result.body["booking"]!!.jsonObject["cancelReason"]!!.jsonPrimitive.content) }
                    }
                } else {
                    started(result); assertEquals(stable, f.state()); assertEquals(initial, proof(f, id))
                    // Fresh operation key: this refusal is not hidden by a successful receipt.
                    started(wire.send(actor, method, path, body, if (method == "POST") UUID.randomUUID() else null))
                    assertEquals(stable, f.state())
                }
                ledger += record(operation.name, offset, f, id, method, path, body, key, result, initial)
                if (offset < 0) {
                    if (operation == Operation.BOOK) {
                        // A real later revision makes historical replay distinguishable from current state.
                        val extra = wire.send(f.observer, "PUT", "/api/v1/proposals/$id/vote", buildJsonObject { put("going", true) })
                        assertEquals(200, extra.status); assertEquals(prepared.revision + 2, extra.body["revision"]!!.jsonPrimitive.long)
                    }
                    f.clock.set(start.plusNanos(1000))
                    val newer = proof(f, id); val beforeLate = f.state()
                    val lateBody = if (operation == Operation.BOOK) booking(prepared.revision + 2) else body
                    if (operation != Operation.CLOSE) started(wire.send(actor, method, path, lateBody, if (method == "POST") UUID.randomUUID() else null))
                    if (key != null) {
                        val replay = wire.send(actor, method, path, body, key)
                        assertEquals(result.status, replay.status); assertEquals(result.body, replay.body)
                        assertEquals(beforeLate, f.state()); assertEquals(newer, proof(f, id))
                        ledger += record(operation.name + "_HISTORICAL_REPLAY", 1, f, id, method, path, body, key, replay, newer)
                    } else assertEquals(beforeLate, f.state())
                }
            }
        }
        write(operation.name.lowercase(), JsonArray(ledger))
    }

    @Test
    fun `vote and book sample controlled server clock after real point lock wait`() = runBlocking<Unit> {
        val ledger = mutableListOf<JsonObject>()
        for (operation in listOf(Operation.VOTE_TRUE, Operation.BOOK)) MeetingAcceptanceFixture(databaseLockTimeoutMs = 30_000, databaseStatementTimeoutMs = 35_000).use { f ->
            server(f, controlled = true) { wire ->
                val prepared = prepare(f, wire, operation); val id = prepared.id; val start = persistedStart(f, id)
                f.clock.set(start.minusNanos(1000))
                val body = if (operation == Operation.BOOK) booking(prepared.revision) else buildJsonObject { put("going", true) }
                val path = "/api/v1/proposals/$id/${if (operation == Operation.BOOK) "booking" else "vote"}"
                val method = if (operation == Operation.BOOK) "POST" else "PUT"
                val key = if (method == "POST") UUID.randomUUID() else null
                val initial = proof(f, id); val stable = f.state()
                lateinit var pending: CompletableFuture<Result>
                var waitProof = buildJsonObject {}
                f.connection().use { gate ->
                    gate.autoCommit = false
                    try {
                        val gatePid = gate.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { r -> check(r.next()); r.getInt(1) } }
                        gate.prepareStatement("SELECT id FROM points WHERE id=? FOR UPDATE").use { s -> s.setObject(1, f.point); s.executeQuery().use { r -> check(r.next()) } }
                        pending = wire.sendAsync(f.voter, method, path, body, key)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                        var seen = false
                        while (!seen && System.nanoTime() < deadline) {
                            f.connection().use { c -> c.prepareStatement("SELECT pid,query FROM pg_stat_activity WHERE datname=current_database() AND state='active' AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid))").use { s ->
                                s.setInt(1, gatePid); s.executeQuery().use { r -> if (r.next()) {
                                    waitProof = buildJsonObject { put("gatePid", gatePid); put("waiterPid", r.getInt(1)); put("query", r.getString(2)) }; seen = true
                                } }
                            } }
                            if (!seen) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
                        }
                        assertTrue(seen); assertFalse(pending.isDone); assertEquals(stable, f.state())
                        f.clock.set(start)
                    } finally { gate.rollback() }
                }
                val result = pending.get(15, TimeUnit.SECONDS)
                started(result); assertEquals(stable, f.state()); assertEquals(initial, proof(f, id))
                ledger += JsonObject(record(operation.name + "_AFTER_LOCK", 0, f, id, method, path, body, key, result, initial).toMutableMap().apply {
                    put("clockBeforeWait", JsonPrimitive(start.minusNanos(1000).toString())); put("actualWait", waitProof)
                })
            }
        }
        write("postlock", JsonArray(ledger))
    }

    @Test
    fun `actual past active booking survives real PostgreSQL reads and expiry tick`() = runBlocking<Unit> {
        MeetingAcceptanceFixture().use { f ->
            val booked = UUID.randomUUID(); val unbooked = UUID.randomUUID(); val bookingId = UUID.randomUUID()
            val witness = databaseNow(f)
            val start = witness.minusSeconds(7200); val end = start.plusSeconds(600)
            f.connection().use { c ->
                c.autoCommit = false
                for ((id, rev) in listOf(booked to 3, unbooked to 2)) {
                    MeetingAcceptanceFixture.execute(c, "INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                        id, f.chats[if (id == booked) 0 else 1], f.author, start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC), rev, start.minusSeconds(3600).atOffset(ZoneOffset.UTC), start.minusSeconds(3500).atOffset(ZoneOffset.UTC))
                    for (actor in listOf(f.author, f.voter)) MeetingAcceptanceFixture.execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,?)", id, actor, start.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                }
                MeetingAcceptanceFixture.execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,?)", bookingId, booked, f.voter, start.minusSeconds(3500).atOffset(ZoneOffset.UTC))
                c.commit()
            }
            assertTrue(databaseNow(f) > end)
            server(f, controlled = false) { wire ->
                val initialBooked = proof(f, booked); val initialUnbooked = proof(f, unbooked)
                assertEquals("OPEN", initialBooked["proposal"]!!.jsonObject["lifecycle"]!!.jsonPrimitive.content)
                assertEquals("OPEN", initialUnbooked["proposal"]!!.jsonObject["lifecycle"]!!.jsonPrimitive.content)
                val canonical = wire.send(f.author, "GET", "/api/v1/proposals/$booked").also { assertEquals(200, it.status) }.body
                assertEquals("booked", canonical["status"]!!.jsonPrimitive.content); assertEquals("active", canonical["booking"]!!.jsonObject["status"]!!.jsonPrimitive.content)
                assertEquals(start, Instant.parse(canonical["startsAt"]!!.jsonPrimitive.content))
                val stable = f.state()
                for ((actor, method, path, body) in listOf(
                    Command(f.voter, "PUT", "/api/v1/proposals/$booked/vote", buildJsonObject { put("going", false) }),
                    Command(f.observer, "PUT", "/api/v1/proposals/$unbooked/vote", buildJsonObject { put("going", true) }),
                    Command(f.author, "POST", "/api/v1/proposals/$unbooked/booking", booking(2)),
                    Command(f.author, "POST", "/api/v1/proposals/$booked/close", revision(3)))) {
                    started(wire.send(actor, method, path, body, if (method == "POST") UUID.randomUUID() else null)); assertEquals(stable, f.state())
                }
                val scheduler = ProposalExpiryScheduler(f.runtime)
                val expired = scheduler.tick()
                assertEquals(listOf(unbooked), expired.map { it.proposalId }); assertEquals(3L, expired.single().revision)
                assertEquals(initialBooked, proof(f, booked))
                assertEquals("CLOSED", proof(f, unbooked)["proposal"]!!.jsonObject["lifecycle"]!!.jsonPrimitive.content)
                assertEquals("TIME", proof(f, unbooked)["proposal"]!!.jsonObject["closed_reason"]!!.jsonPrimitive.content)
                assertEquals(canonical, wire.send(f.author, "GET", "/api/v1/proposals/$booked").body)
                val list = wire.send(f.author, "GET", "/api/v1/chats/${f.chats[0]}/proposals").body["items"]!!.jsonArray
                assertEquals(canonical, list.single { it.jsonObject["id"]!!.jsonPrimitive.content == booked.toString() })
                val afterTick = f.state(); assertTrue(scheduler.tick().isEmpty()); assertEquals(afterTick, f.state())
                write("actual-past-history", buildJsonObject { put("postgresNowBefore", witness.toString()); put("postgresNowAfter", databaseNow(f).toString()); put("startsAt", start.toString()); put("endsAt", end.toString()); put("clock", "PostgreSQL defaults, no injected Clock")
                    put("initialBooked", initialBooked); put("initialUnbooked", initialUnbooked); put("canonicalBookedAfterTick", canonical); put("bookedAfterTick", proof(f, booked)); put("expiredId", unbooked.toString()); put("expiredMessageId", expired.single().message.id.toString()); put("unbookedAfterTick", proof(f, unbooked)) })
            }
        }
    }

    private data class Command(val actor: UUID, val method: String, val path: String, val body: JsonObject)
    private data class Prepared(val id: UUID, val revision: Long)
    private fun prepare(f: MeetingAcceptanceFixture, wire: Native, operation: Operation): Prepared {
        val start = f.anchor.plusNanos(123456000)
        val result = wire.send(f.author, "POST", "/api/v1/chats/${f.chats[0]}/proposals", buildJsonObject {
            put("startsAt", start.atOffset(ZoneOffset.ofHours(3)).toString()); put("endsAt", start.plusSeconds(600).atOffset(ZoneOffset.ofHours(3)).toString())
        }, UUID.randomUUID())
        assertEquals(201, result.status); assertEquals(start.toString(), result.body["startsAt"]!!.jsonPrimitive.content)
        val id = UUID.fromString(result.body["id"]!!.jsonPrimitive.content)
        for (actor in if (operation == Operation.VOTE_TRUE) listOf(f.author) else listOf(f.author, f.voter))
            assertEquals(200, wire.send(actor, "PUT", "/api/v1/proposals/$id/vote", buildJsonObject { put("going", true) }).status)
        val count = if (operation == Operation.VOTE_TRUE) 1L else 2L
        if (operation == Operation.VOTE_FALSE || operation == Operation.CLOSE) {
            assertEquals(201, wire.send(f.voter, "POST", "/api/v1/proposals/$id/booking", booking(count), UUID.randomUUID()).status)
            return Prepared(id, count + 1)
        }
        return Prepared(id, count)
    }
    private suspend fun server(f: MeetingAcceptanceFixture, controlled: Boolean, action: suspend (Native) -> Unit) {
        val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            configureTransport(); configureRouting(f.runtime, sessionAccess = access,
                proposalService = f.proposals(controlledVoteTime = controlled), bookingService = f.bookings(controlledCloseTime = controlled, controlledBookTime = controlled))
        }
        server.start(wait = false)
        try { action(Native(f, server.engine.resolvedConnectors().single().port)) } finally { server.stop(0, 1000) }
    }
    private data class Result(val status: Int, val body: JsonObject)
    private class Native(private val f: MeetingAcceptanceFixture, private val port: Int) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        fun sendAsync(actor: UUID, method: String, path: String, body: JsonObject?, key: UUID?): CompletableFuture<Result> {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(40)).header("Authorization", f.bearer(actor))
            if (body != null) request.header("Content-Type", "application/json")
            if (key != null) request.header("Idempotency-Key", key.toString())
            return client.sendAsync(request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply { Result(it.statusCode(), Json.parseToJsonElement(it.body()).jsonObject) }
        }
        fun send(actor: UUID, method: String, path: String, body: JsonObject? = null, key: UUID? = null) = sendAsync(actor, method, path, body, key).get(45, TimeUnit.SECONDS)
    }
    private fun started(result: Result) { assertEquals(409, result.status); assertEquals("MEETING_ALREADY_STARTED", result.body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content) }
    private fun booking(revision: Long) = buildJsonObject { put("expectedRevision", revision); put("confirmOverlap", false) }
    private fun revision(value: Long) = buildJsonObject { put("expectedRevision", value) }
    private fun persistedStart(f: MeetingAcceptanceFixture, id: UUID): Instant = f.connection().use { c -> c.prepareStatement("SELECT starts_at FROM meeting_proposals WHERE id=?").use { s -> s.setObject(1, id); s.executeQuery().use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant() } } }
    private fun databaseNow(f: MeetingAcceptanceFixture): Instant = f.connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant() } } }
    private fun proof(f: MeetingAcceptanceFixture, id: UUID): JsonObject = f.connection().use { c -> c.prepareStatement("""SELECT jsonb_build_object(
        'proposal',to_jsonb(p),'votes',(SELECT coalesce(jsonb_agg(user_id ORDER BY user_id),'[]'::jsonb) FROM votes WHERE proposal_id=p.id),
        'bookings',(SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY booking_order),'[]'::jsonb) FROM bookings b WHERE proposal_id=p.id),
        'seq',(SELECT last_message_seq FROM chats WHERE id=p.chat_id),
        'messages',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'kind',system_type,'seq',message_seq) ORDER BY message_seq),'[]'::jsonb) FROM messages WHERE chat_id=p.chat_id),
        'notificationCount',(SELECT count(*) FROM notification_events WHERE proposal_id=p.id),
        'jobCount',(SELECT count(*) FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.proposal_id=p.id),
        'outcomeCount',(SELECT count(*) FROM operation_idempotency WHERE actor_user_id IN (?,?,?,?)))::text FROM meeting_proposals p WHERE id=?""").use { s ->
            (f.people + id).forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeQuery().use { r -> check(r.next()); Json.parseToJsonElement(r.getString(1)).jsonObject }
        } }
    private fun record(operation: String, offset: Long, f: MeetingAcceptanceFixture, id: UUID, method: String, path: String, body: JsonObject, key: UUID?, result: Result, initial: JsonObject) = buildJsonObject {
        put("operation", operation); put("offsetMicros", offset); put("persistedStart", persistedStart(f, id).toString()); put("serverClock", f.clock.instant().toString()); put("clockZone", f.clock.zone.id)
        put("method", method); put("path", path); put("body", body); put("operationKey", key?.let { JsonPrimitive(it.toString()) } ?: JsonNull); put("actualStatus", result.status); put("response", result.body); put("initial", initial); put("final", proof(f, id))
    }
    private fun write(name: String, value: JsonElement) {
        val file = Path.of(".local", "t114-evidence", "$name.json"); Files.createDirectories(file.parent); Files.writeString(file, value.toString())
    }
}