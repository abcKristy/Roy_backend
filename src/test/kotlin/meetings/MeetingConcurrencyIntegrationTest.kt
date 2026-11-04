package com.example.meetings

import com.example.accounts.AccountDeletionService
import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionAccessService
import com.example.persistence.JdbcSessionRepository
import com.example.points.PointWriteService
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
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class MeetingConcurrencyIntegrationTest {
    @Test fun `different bookers serialize to one active booking`() = runBlocking<Unit> { exercise(Kind.BOOK_BOOK) }
    @Test fun `same key simultaneous booking replays one identity and order`() = runBlocking<Unit> { exercise(Kind.SAME_KEY) }
    @Test fun `book and unvote cannot leave active booking below threshold`() = runBlocking<Unit> { exercise(Kind.BOOK_UNVOTE) }
    @Test fun `book and real membership exit cannot leave invalid active booking`() = runBlocking<Unit> { exercise(Kind.EXIT_BOOK) }
    @Test fun `author close and vote respect whichever point transition commits first`() = runBlocking<Unit> { exercise(Kind.CLOSE_VOTE) }
    @Test fun `same voter concurrent desired true stores one row and revision`() = runBlocking<Unit> { exercise(Kind.SAME_VOTER) }
    @Test fun `distinct simultaneous voters preserve both rows and one ready edge`() = runBlocking<Unit> { exercise(Kind.DISTINCT_VOTERS) }
    @Test fun `authenticated booking queued behind real account deletion returns stable session failure`() = runBlocking<Unit> { exercise(Kind.DELETE_BOOK) }

    @Test fun `authenticated vote queued behind real account deletion returns stable session failure`() = runBlocking<Unit> { exercise(Kind.DELETE_VOTE) }

    private enum class Kind { BOOK_BOOK, SAME_KEY, BOOK_UNVOTE, EXIT_BOOK, CLOSE_VOTE, SAME_VOTER, DISTINCT_VOTERS, DELETE_BOOK, DELETE_VOTE }
    private suspend fun exercise(kind: Kind) {
        val deletionCase = kind == Kind.DELETE_BOOK || kind == Kind.DELETE_VOTE
        MeetingAcceptanceFixture(databaseLockTimeoutMs = 30_000, databaseStatementTimeoutMs = 35_000).use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val hasher = Argon2idPasswordHasher()
            val localPassword = "SyntheticRacePassword42!"
            if (deletionCase) f.connection().use { c ->
                MeetingAcceptanceFixture.execute(c, "UPDATE users SET password_hash=? WHERE id=?", hasher.hash(localPassword), f.voter)
            }
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport()
                configureRouting(f.runtime, sessionAccess = access, proposalService = f.proposals(), bookingService = f.bookings(),
                    pointWriteService = PointWriteService(f.runtime), accountDeletionService = AccountDeletionService(f.runtime, PasswordHashingWork(hasher)))
            }
            server.start(wait = false)
            try {
                val wire = Native(f, server.engine.resolvedConnectors().single().port)
                val proposal = wire.send(Call(f.author, "POST", "/api/v1/chats/${f.chats[0]}/proposals", buildJsonObject {
                    put("startsAt", f.slots[0].startsAt.toString()); put("endsAt", f.slots[0].endsAt.toString())
                }, UUID.randomUUID())).also { assertEquals(201, it.status) }.body
                val id = UUID.fromString(proposal["id"]!!.jsonPrimitive.content)
                fun vote(actor: UUID, going: Boolean = true) = Call(actor, "PUT", "/api/v1/proposals/$id/vote", buildJsonObject { put("going", going) })
                val initialVoters = when (kind) { Kind.CLOSE_VOTE -> listOf(f.author); Kind.SAME_VOTER, Kind.DISTINCT_VOTERS -> emptyList(); else -> listOf(f.author, f.voter) }
                for (actor in initialVoters) assertEquals(200, wire.send(vote(actor)).status)
                val initial = state(f, id)
                assertEquals(initialVoters.size, initial["voteCount"]!!.jsonPrimitive.int)
                val revision = initial["revision"]!!.jsonPrimitive.long
                val key = UUID.randomUUID()
                fun book(actor: UUID, operationKey: UUID = UUID.randomUUID()) = Call(actor, "POST", "/api/v1/proposals/$id/booking",
                    buildJsonObject { put("confirmOverlap", false); put("expectedRevision", revision) }, operationKey)
                val calls = when (kind) {
                    Kind.BOOK_BOOK -> listOf(book(f.author), book(f.voter))
                    Kind.SAME_KEY -> listOf(book(f.voter, key), book(f.voter, key))
                    Kind.BOOK_UNVOTE -> listOf(book(f.author), vote(f.voter, false))
                    Kind.EXIT_BOOK -> listOf(book(f.author), Call(f.voter, "DELETE", "/api/v1/chats/${f.chats[0]}/membership"))
                    Kind.CLOSE_VOTE -> listOf(Call(f.author, "POST", "/api/v1/proposals/$id/close", buildJsonObject { put("expectedRevision", revision) }, key), vote(f.voter))
                    Kind.SAME_VOTER -> listOf(vote(f.author), vote(f.author))
                    Kind.DISTINCT_VOTERS -> listOf(vote(f.author), vote(f.voter))
                    Kind.DELETE_BOOK, Kind.DELETE_VOTE -> listOf(Call(f.voter, "POST", "/api/v1/me/deletion", buildJsonObject { put("currentPassword", localPassword); put("confirm", true) }), if (kind == Kind.DELETE_BOOK) book(f.voter) else vote(f.voter, false))
                }
                val raced = overlap(f, wire, calls, deletionCase, initial, id)
                val results = raced.results
                val final = state(f, id)
                val evidence = buildJsonObject {
                    put("case", kind.name); put("baseline", "1241e9938824d85bd2d491645bcd06e318fe9a63"); put("initial", initial)
                    put("gatePid", raced.gatePid); put("waitersBeforeRelease", JsonArray(raced.waiters)); put("simultaneousStart", !deletionCase)
                    put("schedule", if (deletionCase) "deletion actual actor-lock waiter first, authenticated meeting mutation actor-lock waiter second, release actor gate" else "two ready workers released by one latch, both actual DB waiters behind held own point before release")
                    put("requests", JsonArray(calls.map { it.safeJson() })); put("responses", JsonArray(results.map { it.json() })); put("final", final)
                }
                val file = Path.of(".local", "t113-evidence", "${kind.name.lowercase()}.json")
                Files.createDirectories(file.parent); Files.writeString(file, evidence.toString())
                assertInvariants(f, wire, id, final)
                when (kind) {
                    Kind.BOOK_BOOK -> {
                        assertEquals(listOf(201, 409), results.map { it.status }.sorted()); assertEquals("STALE_STATE", results.single { it.status == 409 }.code())
                        assertEquals(3L, final["revision"]!!.jsonPrimitive.long); assertEquals(1, final["activeCount"]!!.jsonPrimitive.int)
                        assertEquals(1, final.bookings().size); assertKinds(final, proposed = 1, ready = 1, booked = 1)
                        val winner = results.single { it.status == 201 }.body
                        assertEquals(winner["booking"]!!.jsonObject["id"], JsonPrimitive(final.bookings().single()["id"]!!.jsonPrimitive.content))
                    }
                    Kind.SAME_KEY -> {
                        assertTrue(results.all { it.status == 201 }); assertEquals(results[0].body, results[1].body)
                        assertEquals(3L, final["revision"]!!.jsonPrimitive.long); assertEquals(1, final.bookings().size); assertKinds(final, proposed = 1, ready = 1, booked = 1)
                        val order = results[0].body["booking"]!!.jsonObject["bookingOrder"]!!.jsonPrimitive.long
                        assertEquals(order, final["lastAllocatedOrder"]!!.jsonPrimitive.long)
                        val previousOrder = initial["lastAllocatedOrder"]!!.takeUnless { it == JsonNull }?.jsonPrimitive?.long
                        assertEquals(previousOrder?.plus(1) ?: 1L, order, "This isolated successful same-key race allocates exactly one order")
                        val stable = state(f, id)
                        assertEquals(results[0].body, wire.send(calls[0].copy(body = buildJsonObject { put("confirmOverlap", true); put("expectedRevision", 3) })).body)
                        assertEquals(stable, state(f, id)); assertEquals(2, final["outcomeCount"]!!.jsonPrimitive.int)
                    }
                    Kind.BOOK_UNVOTE, Kind.EXIT_BOOK -> {
                        assertEquals(if (kind == Kind.EXIT_BOOK) 204 else 200, results[1].status)
                        assertEquals(0, final["activeCount"]!!.jsonPrimitive.int); assertEquals(1, final["voteCount"]!!.jsonPrimitive.int)
                        val bookingWon = results[0].status == 201
                        if (!bookingWon) { assertEquals(409, results[0].status); assertEquals("STALE_STATE", results[0].code()) }
                        assertEquals(if (bookingWon) 4L else 3L, final["revision"]!!.jsonPrimitive.long)
                        assertEquals(if (bookingWon) 1 else 0, final.bookings().size)
                        if (bookingWon) { val history = final.bookings().single(); assertEquals("CANCELLED", history["status"]!!.jsonPrimitive.content); assertEquals("INSUFFICIENT_VOTES", history["cancel_reason"]!!.jsonPrimitive.content) }
                        assertKinds(final, proposed = 1, ready = 1, booked = if (bookingWon) 1 else 0, cancelled = if (bookingWon) 1 else 0, left = if (kind == Kind.EXIT_BOOK) 1 else 0)
                        if (kind == Kind.EXIT_BOOK) {
                            assertEquals(403, wire.send(Call(f.voter, "GET", "/api/v1/proposals/$id")).status)
                            val stable = state(f, id); assertEquals(204, wire.send(calls[1]).status); assertEquals(stable, state(f, id))
                        } else { val stable = state(f, id); assertEquals(200, wire.send(calls[1]).status); assertEquals(stable, state(f, id)) }
                    }
                    Kind.CLOSE_VOTE -> {
                        assertEquals(2L, final["revision"]!!.jsonPrimitive.long); assertEquals(0, final["activeCount"]!!.jsonPrimitive.int)
                        if (results[0].status == 200) {
                            assertEquals(409, results[1].status); assertEquals("PROPOSAL_CLOSED", results[1].code()); assertEquals("CLOSED", final["lifecycle"]!!.jsonPrimitive.content)
                            assertEquals(1, final["voteCount"]!!.jsonPrimitive.int); assertKinds(final, proposed = 1, closed = 1)
                        } else {
                            assertEquals(409, results[0].status); assertEquals("STALE_STATE", results[0].code()); assertEquals(200, results[1].status)
                            assertEquals("OPEN", final["lifecycle"]!!.jsonPrimitive.content); assertEquals(2, final["voteCount"]!!.jsonPrimitive.int); assertKinds(final, proposed = 1, ready = 1)
                        }
                    }
                    Kind.SAME_VOTER, Kind.DISTINCT_VOTERS -> {
                        assertTrue(results.all { it.status == 200 }); val count = if (kind == Kind.SAME_VOTER) 1 else 2
                        assertEquals(count, final["voteCount"]!!.jsonPrimitive.int); assertEquals(count.toLong(), final["revision"]!!.jsonPrimitive.long)
                        assertKinds(final, proposed = 1, ready = if (count == 2) 1 else 0)
                        val stable = state(f, id); for (call in calls) assertEquals(200, wire.send(call).status); assertEquals(stable, state(f, id))
                    }
                    Kind.DELETE_BOOK, Kind.DELETE_VOTE -> {
                        assertEquals(204, results[0].status)
                        // This request passed real JWT auth before waiting behind account deletion.
                        assertEquals(401, results[1].status, "Authenticated meeting mutation queued behind committed account deletion must fail as session expiry")
                        assertEquals("SESSION_EXPIRED", results[1].code()); assertEquals(0, final["activeCount"]!!.jsonPrimitive.int)
                        assertEquals(1, final["voteCount"]!!.jsonPrimitive.int); assertEquals(3L, final["revision"]!!.jsonPrimitive.long)
                        assertTrue(final.bookings().isEmpty()); assertKinds(final, proposed = 1, ready = 1, left = 1)
                        val stable = state(f, id); val late = wire.send(calls[1])
                        assertEquals(401, late.status); assertEquals("SESSION_EXPIRED", late.code()); assertEquals(stable, state(f, id))
                    }
                }
                val stable = state(f, id)
                val denied = wire.send(Call(f.outsider, "POST", "/api/v1/proposals/$id/booking", buildJsonObject { put("confirmOverlap", false); put("expectedRevision", final["revision"]!!) }, UUID.randomUUID()))
                assertEquals(403, denied.status); assertEquals("NOT_MEMBER", denied.code()); assertEquals(stable, state(f, id))
            } finally { server.stop(0, 1000) }
        }
    }

    private data class Call(val actor: UUID, val method: String, val path: String, val body: JsonObject? = null, val key: UUID? = null) {
        fun safeJson() = buildJsonObject {
            put("actorId", actor.toString()); put("method", method); put("path", path); put("operationKey", key?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("body", if (path == "/api/v1/me/deletion") buildJsonObject { put("confirm", true); put("passwordOmitted", true) } else body ?: JsonNull)
        }
    }
    private data class Result(val status: Int, val body: JsonObject) {
        fun code() = body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        fun json() = buildJsonObject { put("status", status); put("body", body) }
    }
    private class Native(private val f: MeetingAcceptanceFixture, private val port: Int) {
        private val clients = f.people.associateWith { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }
        fun send(call: Call): Result {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port${call.path}")).timeout(Duration.ofSeconds(40)).header("Authorization", f.bearer(call.actor))
            if (call.body != null) builder.header("Content-Type", "application/json")
            if (call.key != null) builder.header("Idempotency-Key", call.key.toString())
            val response = clients.getValue(call.actor).send(builder.method(call.method, call.body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            return Result(response.statusCode(), if (response.body().isBlank()) buildJsonObject {} else Json.parseToJsonElement(response.body()).jsonObject)
        }
    }
    private data class Overlap(val results: List<Result>, val waiters: List<JsonObject>, val gatePid: Int)
    private fun overlap(f: MeetingAcceptanceFixture, wire: Native, calls: List<Call>, deletionFirst: Boolean, initial: JsonObject, proposalId: UUID): Overlap {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures: List<Future<Result>>
            var observed = emptyList<JsonObject>()
            var gatePid = 0
            f.connection().use { gate ->
                gate.autoCommit = false
                gatePid = gate.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { r -> check(r.next()); r.getInt(1) } }
                try {
                    gate.prepareStatement(if (deletionFirst) "SELECT id FROM users WHERE id=? FOR NO KEY UPDATE" else "SELECT id FROM points WHERE id=? FOR UPDATE").use { s ->
                        s.setObject(1, if (deletionFirst) f.voter else f.point); s.executeQuery().use { rows -> check(rows.next()) }
                    }
                    if (deletionFirst) {
                        // Deliberate overlapping schedule: deletion has authenticated/verified its password
                        // and reached its real actor lock before a second authenticated booking queues.
                        val deletion = pool.submit<Result> { wire.send(calls[0]) }
                        awaitWaiters(f, 1)
                        val booking = pool.submit<Result> { wire.send(calls[1]) }
                        futures = listOf(deletion, booking)
                    } else {
                        val ready = CountDownLatch(2); val start = CountDownLatch(1)
                        futures = calls.map { call -> pool.submit<Result> {
                            ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); wire.send(call)
                        } }
                        assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
                    }
                    observed = awaitWaiters(f, 2)
                    fun reachesGate(pid: Int, visited: Set<Int> = emptySet()): Boolean {
                        if (pid == gatePid) return true
                        if (pid in visited) return false
                        val waiter = observed.singleOrNull { it["pid"]!!.jsonPrimitive.int == pid } ?: return false
                        val blockers = waiter["blockers"]!!.jsonPrimitive.content.removeSurrounding("{", "}").split(',').mapNotNull { it.toIntOrNull() }
                        return blockers.any { reachesGate(it, visited + pid) }
                    }
                    assertTrue(observed.all { reachesGate(it["pid"]!!.jsonPrimitive.int) }, "Both actual blocker chains must reach our own gate connection")
                    assertTrue(futures.none { it.isDone }); assertEquals(initial, state(f, proposalId), "Both commands remain uncommitted behind own DB gate")
                } finally { gate.rollback() }
            }
            return Overlap(futures.map { it.get(20, TimeUnit.SECONDS) }, observed, gatePid)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    private fun awaitWaiters(f: MeetingAcceptanceFixture, count: Int): List<JsonObject> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            val rows = f.connection().use { c -> c.createStatement().use { s ->
                s.executeQuery("SELECT pid,wait_event,pg_blocking_pids(pid)::text,query FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0 ORDER BY pid").use { r -> buildList {
                    while (r.next()) add(buildJsonObject { put("pid", r.getInt(1)); put("waitEvent", r.getString(2)); put("blockers", r.getString(3)); put("query", r.getString(4)) })
                } }
            } }
            if (rows.size == count) return rows
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
        } while (System.nanoTime() < deadline)
        fail("Did not observe $count actual overlapping DB lock waiters within bounded deadline")
    }

    private fun state(f: MeetingAcceptanceFixture, id: UUID): JsonObject = f.connection().use { c -> c.prepareStatement("""SELECT jsonb_build_object(
        'proposalId',p.id,'revision',p.revision,'lifecycle',p.lifecycle,
        'voteCount',(SELECT count(*) FROM votes WHERE proposal_id=p.id),
        'validVoteCount',(SELECT count(*) FROM votes v JOIN users u ON u.id=v.user_id AND u.deleted_at IS NULL JOIN chat_memberships m ON m.chat_id=p.chat_id AND m.user_id=v.user_id AND m.left_at IS NULL WHERE v.proposal_id=p.id),
        'duplicateVotes',(SELECT count(*) FROM (SELECT user_id FROM votes WHERE proposal_id=p.id GROUP BY user_id HAVING count(*)>1) d),
        'activeCount',(SELECT count(*) FROM bookings WHERE proposal_id=p.id AND status='ACTIVE'),
        'bookings',(SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY booking_order),'[]'::jsonb) FROM bookings b WHERE proposal_id=p.id),
        'votes',(SELECT coalesce(jsonb_agg(user_id ORDER BY user_id),'[]'::jsonb) FROM votes WHERE proposal_id=p.id),
        'messages',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'seq',message_seq,'kind',system_type,'system',system_payload) ORDER BY message_seq),'[]'::jsonb) FROM messages WHERE chat_id=p.chat_id),
        'seq',(SELECT last_message_seq FROM chats WHERE id=p.chat_id),
        'notificationCount',(SELECT count(*) FROM notification_events WHERE proposal_id=p.id),
        'notificationDuplicates',(SELECT count(*) FROM (SELECT recipient_user_id,dedupe_key FROM notification_events WHERE proposal_id=p.id GROUP BY recipient_user_id,dedupe_key HAVING count(*)>1) d),
        'outcomeCount',(SELECT count(*) FROM operation_idempotency WHERE actor_user_id IN (?,?,?,?)),
        'lastAllocatedOrder',pg_sequence_last_value(pg_get_serial_sequence('bookings','booking_order')::regclass))::text FROM meeting_proposals p WHERE id=?""").use { s ->
            (f.people + id).forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeQuery().use { rows -> check(rows.next()); Json.parseToJsonElement(rows.getString(1)).jsonObject }
        } }
    private fun JsonObject.bookings() = getValue("bookings").jsonArray.map { it.jsonObject }
    private fun assertInvariants(f: MeetingAcceptanceFixture, wire: Native, id: UUID, state: JsonObject) {
        val count = state["voteCount"]!!.jsonPrimitive.int
        assertEquals(count, state["validVoteCount"]!!.jsonPrimitive.int); assertEquals(0, state["duplicateVotes"]!!.jsonPrimitive.int)
        assertEquals(0, state["notificationDuplicates"]!!.jsonPrimitive.int)
        val active = state["activeCount"]!!.jsonPrimitive.int; assertTrue(active in 0..1)
        if (active == 1) { assertTrue(count >= 2); assertEquals("OPEN", state["lifecycle"]!!.jsonPrimitive.content) }
        val messages = state["messages"]!!.jsonArray
        assertEquals(messages.size.toLong(), state["seq"]!!.jsonPrimitive.long)
        assertEquals((1L..messages.size.toLong()).toList(), messages.map { it.jsonObject["seq"]!!.jsonPrimitive.long })
        val rest = wire.send(Call(f.author, "GET", "/api/v1/proposals/$id")); assertEquals(200, rest.status)
        assertEquals(count, rest.body["goingCount"]!!.jsonPrimitive.int); assertEquals(state["revision"], rest.body["revision"])
        assertEquals(state["votes"]!!.jsonArray.any { it.jsonPrimitive.content == f.author.toString() }, rest.body["viewerGoing"]!!.jsonPrimitive.boolean)
        if (state.bookings().isEmpty()) assertEquals(JsonNull, rest.body["booking"]) else {
            val latest = state.bookings().last(); val projected = rest.body["booking"]!!.jsonObject
            assertEquals(latest["id"], projected["id"]); assertEquals(latest["booking_order"], projected["bookingOrder"])
            assertEquals(latest["status"]!!.jsonPrimitive.content.lowercase(), projected["status"]!!.jsonPrimitive.content)
        }
    }
    private fun assertKinds(state: JsonObject, proposed: Int = 0, ready: Int = 0, booked: Int = 0, cancelled: Int = 0, closed: Int = 0, left: Int = 0) {
        val actual = state["messages"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }.groupingBy { it }.eachCount()
        val expected = mapOf("meeting_proposed" to proposed, "meeting_ready" to ready, "meeting_booked" to booked, "meeting_booking_cancelled" to cancelled, "meeting_closed" to closed, "member_left" to left).filterValues { it > 0 }
        assertEquals(expected, actual)
    }
}