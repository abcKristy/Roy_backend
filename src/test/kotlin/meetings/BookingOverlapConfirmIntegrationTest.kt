package com.example.meetings

import com.example.api.proposalJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.testing.testDatabaseUrl
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class BookingOverlapConfirmIntegrationTest {
    @Test
    fun `confirmation waiting on point lock rechecks all changed guards after warning`() = runBlocking<Unit> {
        val cases = listOf("revision" to "STALE_STATE", "membership" to "NOT_MEMBER", "own vote" to "VOTE_REQUIRED",
            "vote count" to "INSUFFICIENT_VOTES", "lifecycle" to "PROPOSAL_CLOSED", "active booking" to "ALREADY_BOOKED")
        for ((change, code) in cases) Fixture().use { f ->
            f.seedBooking(f.chats[0],10,20)
            val candidate=f.proposal(f.chats[1],12,18)
            Http(f).use { http ->
                val key=UUID.randomUUID()
                assertCode(http.post(candidate,key,"false",2,409),"OVERLAP_CONFIRM_REQUIRED")
                val executor=Executors.newSingleThreadExecutor()
                try {
                    f.pointGate().use { gate ->
                        val future=executor.submit<JsonObject> { http.post(candidate,key,"true",2,if(change=="membership") 403 else 409) }
                        f.waitForPointWaiters(gate)
                        assertFalse(future.isDone,"Confirmation must wait for point serialization")
                        f.mutate(gate,candidate,change)
                        val changed = f.snapshot(gate)
                        gate.commit()
                        // The request may now complete; snapshot includes only the committed fixture mutation.
                        assertCode(future.get(15,TimeUnit.SECONDS),code)
                        assertEquals(changed,f.snapshot(),"Fresh guard refusal must add no effects: $change")
                    }
                    val after=f.snapshot()
                    assertCode(http.post(candidate,key,"true",2,if(change=="membership") 403 else 409),code)
                    assertEquals(after,f.snapshot(),"Repeated current guard refusal has no effects: $change")
                    f.connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM operation_idempotency").use { r -> check(r.next()); assertEquals(0,r.getInt(1)) } } }
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test
    fun `confirmation rechecks database start time after actual point lock wait`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.seedBooking(f.chats[0],-10000000,10000000)
            Http(f).use { http ->
                val candidate=f.nearStartProposal(); val key=UUID.randomUUID()
                assertCode(http.post(candidate,key,"false",2,409),"OVERLAP_CONFIRM_REQUIRED")
                val before=f.snapshot(); val executor=Executors.newSingleThreadExecutor()
                try { f.pointGate().use { gate ->
                    val future=executor.submit<JsonObject> { http.post(candidate,key,"true",2,409) }
                    f.waitForPointWaiters(gate)
                    f.awaitStarted(candidate)
                    gate.commit()
                    assertCode(future.get(15,TimeUnit.SECONDS),"MEETING_ALREADY_STARTED")
                    assertEquals(before,f.snapshot())
                } } finally { executor.shutdownNow() }
            }
        }
    }

    @Test
    fun `explicit confirmation sees newly committed direct conflicts and preserves existing orders`() = runBlocking<Unit> {
        Fixture().use { f ->
            val removed=f.seedBooking(f.chats[0],10,20)
            val survivor=f.seedBooking(f.chats[1],11,19)
            val candidate=f.proposal(f.chats[1],12,18)
            Http(f).use { http ->
                val key=UUID.randomUUID()
                val warning=http.post(candidate,key,"false",2,409)
                assertEquals(listOf(removed.bookingId.toString(),survivor.bookingId.toString()),conflicts(warning.getValue("error").jsonObject.getValue("details").jsonObject,"overlaps"))
                val executor=Executors.newSingleThreadExecutor()
                try { f.pointGate().use { gate ->
                    val future=executor.submit<JsonObject> { http.post(candidate,key,"true",2,201) }
                    f.waitForPointWaiters(gate)
                    f.cancel(gate,removed.bookingId)
                    val added=f.addBooking(gate,f.chats[0],13,17)
                    gate.commit()
                    val existing=f.bookingRows()
                    val created=future.get(15,TimeUnit.SECONDS)
                    assertEquals(listOf(survivor.bookingId.toString(),added.toString()),conflicts(created.getValue("booking").jsonObject,"overlappingActive"))
                    assertEquals("booked",created.getValue("status").jsonPrimitive.content)
                    val after=f.bookingRows()
                    for (row in existing) assertEquals(row,after.single { it[0]==row[0] })
                    assertEquals("CANCELLED",after.single { it[0]==removed.bookingId.toString() }[1])
                    assertEquals(survivor.bookingOrder.toString(),after.single { it[0]==survivor.bookingId.toString() }[2])
                    assertEquals(removed.bookingOrder.toString(),after.single { it[0]==removed.bookingId.toString() }[2])
                    assertEquals(4,after.size)
                    val snapshot=f.snapshot()
                    assertEquals(created,http.post(candidate,key,"true",2,201))
                    assertEquals(snapshot,f.snapshot())
                } } finally { executor.shutdownNow() }
            }
        }
    }

    @Test
    fun `different actors confirming across topics actually serialize on point and both retain active bookings`() = runBlocking<Unit> {
        Fixture().use { f ->
            val original=f.seedBooking(f.chats[0],10,20)
            val a=f.proposal(f.chats[0],12,18); val b=f.proposal(f.chats[1],13,17)
            val ka=UUID.randomUUID(); val kb=UUID.randomUUID()
            fun command(actor:UUID,id:UUID,key:UUID,confirm:Boolean)=BookMeetingCommand(actor,id,key,confirm,2)
            assertIs<BookMeetingResult.OverlapConfirmRequired>(f.service.book(command(f.actor,a,ka,false)) { _,_ -> error("warning") })
            assertIs<BookMeetingResult.OverlapConfirmRequired>(f.service.book(command(f.peer,b,kb,false)) { _,_ -> error("warning") })
            f.pointGate().use { gate ->
                val first=async(Dispatchers.IO) { f.service.book(command(f.actor,a,ka,true)) { _,view -> proposalJson(view) } }
                val second=async(Dispatchers.IO) { f.service.book(command(f.peer,b,kb,true)) { _,view -> proposalJson(view) } }
                f.waitForPointWaiters(gate,2)
                assertFalse(first.isCompleted); assertFalse(second.isCompleted)
                gate.commit()
                val outcomes=listOf(assertIs<BookMeetingResult.Created>(first.await()),assertIs<BookMeetingResult.Created>(second.await()))
                    .sortedBy { it.proposal.booking!!.bookingOrder }
                val early=outcomes[0].proposal.booking!!; val late=outcomes[1].proposal.booking!!
                assertTrue(original.bookingOrder<early.bookingOrder); assertTrue(early.bookingOrder<late.bookingOrder)
                assertEquals(listOf(original.bookingId),early.overlappingActive.map { it.bookingId })
                assertEquals(listOf(original.bookingId,early.id),late.overlappingActive.map { it.bookingId })
                val rows=f.bookingRows(); assertEquals(3,rows.size); assertTrue(rows.all { it[1]=="ACTIVE" })
                assertEquals(original.bookingOrder.toString(),rows.single { it[0]==original.bookingId.toString() }[2])
            }
        }
    }
    private fun assertCode(response:JsonObject,expected:String) = assertEquals(expected,response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    private fun conflicts(value:JsonObject,key:String)=value.getValue(key).jsonArray.map { it.jsonObject.getValue("bookingId").jsonPrimitive.content }

    private class Http(f: Fixture) : AutoCloseable {
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            configureTransport()
            configureRouting(f.runtime, sessionAccess = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime)),
                proposalService = ProposalService(f.runtime, f.secret), bookingService = BookingService(f.runtime))
        }
        private val bearer = f.bearer()
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val port: Int
        init { server.start(wait = false); port = runBlocking { server.engine.resolvedConnectors().single().port } }
        fun post(id: UUID, key: UUID, confirm: String?, revision: Long, expected: Int): JsonObject {
            val body = if (confirm == null) """{"expectedRevision":$revision}""" else """{"confirmOverlap":$confirm,"expectedRevision":$revision}"""
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/$id/booking"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", bearer).header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(expected, response.statusCode(), "Synthetic confirmOverlap=$confirm revision=$revision")
            return Json.parseToJsonElement(response.body()).jsonObject
        }
        override fun close() { server.stop(0, 1000) }
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_overlap_confirm$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-overlap-query-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            secret, appEnvironment = AppEnvironment.LOCAL, databaseStatementTimeoutMs = 15000, databaseLockTimeoutMs = 10000)).start()
        val service = BookingService(runtime)
        val actor = UUID.randomUUID()
        val peer = UUID.randomUUID()
        private val points = List(2) { UUID.randomUUID() }
        val chats = List(3) { UUID.randomUUID() }
        private val start = Instant.parse("2030-01-01T12:00:00Z")
        private val bookedAt = Instant.parse("2026-01-01T00:00:00Z")

        private val session = UUID.randomUUID()
        fun bearer(): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun pointGate(): Connection = connection().also { c ->
            c.autoCommit = false
            c.prepareStatement("SELECT 1 FROM points WHERE id=? FOR UPDATE").use { s -> s.setObject(1, points[0]); s.executeQuery().use { check(it.next()) } }
        }
        fun waitForPointWaiters(blocker: Connection, expected: Int = 1) {
            val pid = blocker.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { r -> check(r.next()); r.getInt(1) } }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            connection().use { observer ->
                do {
                    val count = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT 1 FROM points WHERE id=%FOR UPDATE%' AND cardinality(pg_blocking_pids(pid))>0 AND EXISTS (SELECT 1 FROM pg_stat_activity root_waiter WHERE root_waiter.datname=current_database() AND ?=ANY(pg_blocking_pids(root_waiter.pid)))").use { s ->
                        s.setInt(1, pid); s.executeQuery().use { r -> check(r.next()); r.getInt(1) }
                    }
                    if (count >= expected) return
                    Thread.sleep(20)
                } while (System.nanoTime() < deadline)
            }
            error("Expected $expected real point-lock waiters")
        }
        fun mutate(c: Connection, id: UUID, kind: String) {
            when (kind) {
                "revision" -> execute(c, "UPDATE meeting_proposals SET revision=3 WHERE id=?", id)
                "membership" -> execute(c, "UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?", chats[1], actor)
                "own vote" -> execute(c, "DELETE FROM votes WHERE proposal_id=? AND user_id=?", id, actor)
                "vote count" -> execute(c, "DELETE FROM votes WHERE proposal_id=? AND user_id=?", id, peer)
                "lifecycle" -> execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", id)
                "active booking" -> execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())", UUID.randomUUID(), id, actor)
                else -> error("Unknown fixture mutation")
            }
        }
        fun cancel(c: Connection, id: UUID) = execute(c, "UPDATE bookings SET status='CANCELLED',cancelled_at=clock_timestamp(),cancel_reason='CLOSED_BY_AUTHOR' WHERE id=?", id)
        fun addBooking(c: Connection, chat: UUID, from: Long, to: Long): UUID {
            val proposal = UUID.randomUUID(); val booking = UUID.randomUUID()
            insertProposal(c, proposal, chat, from, to)
            execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())", booking, proposal, actor)
            execute(c, "UPDATE meeting_proposals SET revision=3 WHERE id=?", proposal)
            return booking
        }
        fun bookingRows(): List<List<String?>> = connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id,status,booking_order FROM bookings ORDER BY booking_order").use { r -> buildList { while(r.next()) add(listOf(r.getString(1),r.getString(2),r.getString(3))) } } } }
        fun nearStartProposal(): UUID = connection().use { c ->
            c.autoCommit=false
            val id=UUID.randomUUID()
            execute(c, "INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at) VALUES (?,?,?,clock_timestamp()+interval '3 seconds',clock_timestamp()+interval '1 hour',2,clock_timestamp(),clock_timestamp())",id,chats[1],actor)
            for (person in listOf(actor,peer)) execute(c,"INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())",id,person)
            c.commit(); id
        }
        fun awaitStarted(id: UUID) {
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            connection().use { c -> do {
                val started=c.prepareStatement("SELECT clock_timestamp()>=starts_at FROM meeting_proposals WHERE id=?").use { s -> s.setObject(1,id); s.executeQuery().use { r -> check(r.next()); r.getBoolean(1) } }
                if(started) return
                Thread.sleep(20)
            } while(System.nanoTime()<deadline) }
            error("Fixture proposal did not reach database startsAt")
        }
        fun revision(id: UUID, value: Long) = connection().use { c -> execute(c, "UPDATE meeting_proposals SET revision=? WHERE id=?", value, id) }
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in listOf(actor, peer)) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Overlap query fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)", session, actor, session.toString().replace("-", "").repeat(2))
                    for ((index, point) in points.withIndex()) execute(c,
                        """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                            VALUES (?,ST_SetSRID(ST_MakePoint(?,55.75),4326),'Synthetic overlap fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""",
                        point, 37.62 + index, actor)
                    for ((index, chat) in chats.withIndex()) {
                        val topic = UUID.randomUUID()
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())",
                            topic, points[if (index == 2) 1 else 0], if (index == 1) "basketball" else "football")
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                        for (person in listOf(actor, peer)) execute(c,
                            "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, person)
                    }
                    c.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun proposal(chat: UUID, from: Long, to: Long): UUID = connection().use { c ->
            c.autoCommit = false
            val id = UUID.randomUUID()
            insertProposal(c, id, chat, from, to)
            c.commit()
            id
        }
        private fun insertProposal(c: Connection, id: UUID, chat: UUID, from: Long, to: Long) {
            execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                VALUES (?,?,?,?,?,2,?,?)""", id, chat, actor, time(from), time(to), bookedAt.atOffset(ZoneOffset.UTC), bookedAt.atOffset(ZoneOffset.UTC))
            for (person in listOf(actor, peer)) execute(c,
                "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,?)", id, person, bookedAt.atOffset(ZoneOffset.UTC))
        }
        fun seedBooking(chat: UUID, from: Long, to: Long, cancelled: Boolean = false): BookingConflictView = connection().use { c ->
            c.autoCommit = false
            val proposal = UUID.randomUUID(); val booking = UUID.randomUUID()
            insertProposal(c, proposal, chat, from, to)
            c.prepareStatement("INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,?) RETURNING booking_order").use { s ->
                s.setObject(1, booking); s.setObject(2, proposal); s.setObject(3, actor); s.setObject(4, bookedAt.atOffset(ZoneOffset.UTC))
                s.executeQuery().use { r ->
                    check(r.next())
                    val order = r.getLong(1)
                    execute(c, "UPDATE meeting_proposals SET revision=3 WHERE id=?", proposal)
                    if (cancelled) execute(c, "UPDATE bookings SET status='CANCELLED',cancelled_at=?,cancel_reason='INSUFFICIENT_VOTES' WHERE id=?",
                        bookedAt.plusSeconds(3600).atOffset(ZoneOffset.UTC), booking)
                    c.commit()
                    BookingConflictView(booking, proposal, start.plusSeconds(from * 60), start.plusSeconds(to * 60), bookedAt, order)
                }
            }
        }
        private fun time(minutes: Long) = start.plusSeconds(minutes * 60).atOffset(ZoneOffset.UTC)
        fun deleteCandidate(id: UUID) = connection().use { c -> execute(c, "DELETE FROM meeting_proposals WHERE id=?", id) }

        fun snapshot(): List<List<List<String?>>> = connection().use(::snapshot)
        fun snapshot(c: Connection): List<List<List<String?>>> {
            val queries = listOf(
                "SELECT b.* FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id JOIN chats c ON c.id=p.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY b.id",
                "SELECT p.* FROM meeting_proposals p JOIN chats c ON c.id=p.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY p.id",
                "SELECT v.* FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id JOIN chats c ON c.id=p.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY v.proposal_id,v.user_id",
                "SELECT id,updated_at FROM points WHERE id IN (?,?) ORDER BY id",
                "SELECT c.id,c.last_message_seq FROM chats c JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY c.id",
                "SELECT m.id,m.message_seq FROM messages m JOIN chats c ON c.id=m.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY m.id",
                "SELECT id,event_type,dedupe_key FROM notification_events WHERE recipient_user_id IN (?,?) ORDER BY id",
                "SELECT actor_user_id,operation_type,idempotency_key,response_snapshot FROM operation_idempotency WHERE actor_user_id IN (?,?) ORDER BY actor_user_id,operation_type,idempotency_key",
            )
            return queries.mapIndexed { index, query ->
                c.prepareStatement(query).use { s ->
                    val ids = if (index >= 6) listOf(actor, peer) else points
                    ids.forEachIndexed { n, id -> s.setObject(n + 1, id) }
                    s.executeQuery().use { r -> buildList {
                        while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
                    } }
                }
            } + listOf(sequence(c))
        }
        private fun sequence(c: Connection): List<List<String?>> {
            val name = c.createStatement().use { s -> s.executeQuery("SELECT pg_get_serial_sequence('bookings','booking_order')").use { r -> check(r.next()); r.getString(1) } }
            require(Regex("^[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*$").matches(name))
            return c.createStatement().use { s -> s.executeQuery("SELECT last_value,is_called FROM $name").use { r ->
                check(r.next()); listOf(listOf(r.getString(1), r.getString(2)))
            } }
        }
        override fun close() {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (point in points) execute(c, "DELETE FROM points WHERE id=?", point)
                    for (person in listOf(actor, peer)) execute(c, "DELETE FROM users WHERE id=?", person)
                    c.commit()
                }
            } finally { runtime.close() }
        }
    }
    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any?) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { n, value -> s.setObject(n + 1, value) }; s.executeUpdate() }
        }
    }
}
