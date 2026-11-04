package com.example.meetings

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
import java.util.UUID
import kotlin.test.*

class BookingGuardsIntegrationTest {
    @Test
    fun `native booking guards distinguish eligibility and reject non Long revision without effects`() = runBlocking<Unit> {
        Fixture().use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport()
                configureRouting(f.runtime, sessionAccess = access,
                    proposalService = ProposalService(f.runtime, f.secret), bookingService = BookingService(f.runtime))
            }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                fun request(actor: UUID?, proposal: UUID, body: String?, expected: Int): JsonObject {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/$proposal${if (body == null) "" else "/booking"}"))
                        .timeout(Duration.ofSeconds(20))
                    if (actor != null) builder.header("Authorization", f.bearer(actor))
                    val command = if (body == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                    val response = client.send(command, HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, response.statusCode(), "Synthetic booking DTO=$body; proposal=$proposal")
                    return Json.parseToJsonElement(response.body()).jsonObject
                }
                fun body(revision: String) = """{"confirmOverlap":false,"expectedRevision":$revision}"""
                fun reject(proposal: UUID, revision: String, code: String, status: Int = 409, actor: UUID? = f.booker) {
                    val before = f.state()
                    val error = request(actor, proposal, body(revision), status)
                    assertEquals(code, error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertEquals(before, f.state(), "Guard refusal must not change scoped bookings/votes/revisions/effects/outcomes")
                }
                // Each target has all earlier guards satisfied, so its named guard is observable.
                reject(f.ids.getValue("zero"), "0", "VOTE_REQUIRED")
                reject(f.ids.getValue("one"), "1", "INSUFFICIENT_VOTES")
                reject(f.ids.getValue("no-own"), "2", "VOTE_REQUIRED")
                reject(f.ids.getValue("stale"), "1", "STALE_STATE")
                reject(f.ids.getValue("closed"), "2", "PROPOSAL_CLOSED")
                reject(f.ids.getValue("started"), "2", "MEETING_ALREADY_STARTED")
                reject(f.ids.getValue("booked"), "3", "ALREADY_BOOKED")
                reject(f.ids.getValue("eligible"), "2", "NOT_MEMBER", 403, f.outsider)
                reject(f.ids.getValue("eligible"), "2", "AUTH_REQUIRED", 401, null)
                reject(UUID.randomUUID(), "2", "RESOURCE_GONE", 404)
                val eligible = f.ids.getValue("eligible")
                val created = request(f.booker, eligible, body("2"), 201)
                assertEquals("booked", created["status"]!!.jsonPrimitive.content)
                assertEquals(3L, created["revision"]!!.jsonPrimitive.long)
                assertEquals(true, created["viewerGoing"]!!.jsonPrimitive.boolean)
                assertEquals(f.proposer.toString(), created["proposedByUserId"]!!.jsonPrimitive.content)
                assertEquals(f.booker.toString(), created["booking"]!!.jsonObject["bookedByUserId"]!!.jsonPrimitive.content)
                assertNotEquals(f.proposer, f.booker)
                assertEquals(created, request(f.booker, eligible, null, 200))
                f.connection().use { c ->
                    c.prepareStatement("SELECT id,booked_by_user_id,status FROM bookings WHERE proposal_id=?").use { s ->
                        s.setObject(1, eligible)
                        s.executeQuery().use { r ->
                            assertTrue(r.next()); assertEquals(created["booking"]!!.jsonObject["id"]!!.jsonPrimitive.content, r.getObject(1).toString())
                            assertEquals(f.booker, r.getObject(2, UUID::class.java)); assertEquals("ACTIVE", r.getString(3)); assertFalse(r.next())
                        }
                    }
                }
                // Fresh eligible proposal, fresh keys and native confirmOverlap=false isolate revision parsing.
                val typed = f.ids.getValue("typed")
                for (invalid in listOf("\"2\"", "\"2.0\"", "null", "true", "{}", "[]", "2.5", "2.0", "9223372036854775808", "-1")) {
                    reject(typed, invalid, "INVALID_FIELD", 422)
                }
                val beforeMissing = f.state()
                val missing = request(f.booker, typed, """{"confirmOverlap":false}""", 422)
                assertEquals("INVALID_FIELD", missing["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertEquals(beforeMissing, f.state())
                reject(typed, Long.MAX_VALUE.toString(), "STALE_STATE")
            } finally { server.stop(0, 1000) }
        }
    }

    private data class State(val bookings: List<List<String?>>, val votes: List<List<String?>>, val proposals: List<List<String?>>,
        val messages: Long, val notifications: Long, val outcomes: Long, val sequence: Long)
    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_booking_guards$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-booking-guards-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val proposer = UUID.randomUUID(); val booker = UUID.randomUUID(); val peer = UUID.randomUUID(); val outsider = UUID.randomUUID()
        private val people = listOf(proposer, booker, peer, outsider)
        private val sessions = people.associateWith { UUID.randomUUID() }
        private val point = UUID.randomUUID(); private val chat = UUID.randomUUID()
        val ids = listOf("zero", "one", "no-own", "eligible", "stale", "closed", "started", "booked", "typed").associateWith { UUID.randomUUID() }
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Booking guard fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic booking guard fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, proposer)
                    val topic = UUID.randomUUID()
                    execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in listOf(proposer, booker, peer)) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    for ((index, entry) in ids.entries.withIndex()) {
                        val (name, id) = entry
                        val voters = when (name) { "zero" -> emptyList(); "one" -> listOf(booker); "no-own" -> listOf(proposer, peer); else -> listOf(proposer, booker) }
                        val revision = if (name == "booked") 3 else voters.size
                        val hours = if (name == "started") -2 else 24 + index * 2
                        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                            VALUES (?,?,?,clock_timestamp()+?::interval,clock_timestamp()+?::interval+interval '1 hour',?,clock_timestamp(),clock_timestamp())""",
                            id, chat, proposer, "$hours hours", "$hours hours", revision)
                        for (voter in voters) execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", id, voter)
                        if (name == "closed") execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", id)
                        if (name == "booked") execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())", UUID.randomUUID(), id, proposer)
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
            fun rows(sql: String): List<List<String?>> = c.prepareStatement(sql).use { s ->
                s.setObject(1, chat); s.executeQuery().use { r -> buildList {
                    while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
                } }
            }
            fun count(sql: String): Long = c.prepareStatement(sql).use { s -> s.setObject(1, chat); s.executeQuery().use { r -> check(r.next()); r.getLong(1) } }
            val outcomes = c.prepareStatement("SELECT count(*) FROM operation_idempotency WHERE actor_user_id IN (?,?,?,?)").use { s ->
                people.forEachIndexed { index, id -> s.setObject(index + 1, id) }
                s.executeQuery().use { r -> check(r.next()); r.getLong(1) }
            }
            State(rows("SELECT b.id,b.proposal_id,b.booked_by_user_id,b.booking_order,b.status,b.created_at,b.cancelled_at,b.cancel_reason FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id=? ORDER BY b.id"),
                rows("SELECT v.proposal_id,v.user_id,v.created_at FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=? ORDER BY v.proposal_id,v.user_id"),
                rows("SELECT id,revision,lifecycle,closed_reason,updated_at FROM meeting_proposals WHERE chat_id=? ORDER BY id"),
                count("SELECT count(*) FROM messages WHERE chat_id=?"), count("SELECT count(*) FROM notification_events WHERE chat_id=?"),
                outcomes,
                count("SELECT last_message_seq FROM chats WHERE id=?"))
        }
        override fun close() {
            try {
                connection().use { c ->
                    c.autoCommit = false; execute(c, "DELETE FROM points WHERE id=?", point)
                    for (id in people) execute(c, "DELETE FROM users WHERE id=?", id)
                    c.commit()
                }
            } finally { runtime.close() }
        }
    }
    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}
