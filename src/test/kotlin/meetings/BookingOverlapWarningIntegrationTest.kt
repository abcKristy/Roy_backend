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
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class BookingOverlapWarningIntegrationTest {
    @Test
    fun `native warning has current direct conflicts and no effects then same key permits explicit confirmation`() = runBlocking<Unit> {
        Fixture().use { f ->
            val first = f.seedBooking(f.chats[0], 10, 20)
            val second = f.seedBooking(f.chats[1], 12, 18)
            val candidate = f.proposal(f.chats[1], 14, 16)
            Http(f).use { http ->
                val key = UUID.randomUUID()
                fun warning(revision: Long): JsonObject {
                    val before = f.snapshot()
                    val response = http.post(candidate, key, "false", revision, 409)
                    val error = response.getValue("error").jsonObject
                    assertEquals("OVERLAP_CONFIRM_REQUIRED", error.getValue("code").jsonPrimitive.content)
                    val details = error.getValue("details").jsonObject
                    assertEquals(setOf("overlaps"), details.keys)
                    val expected = JsonArray(listOf(first, second).map { conflict -> buildJsonObject {
                        put("bookingId", conflict.bookingId.toString()); put("proposalId", conflict.proposalId.toString())
                        put("startsAt", conflict.startsAt.toString()); put("endsAt", conflict.endsAt.toString())
                        put("bookedAt", conflict.bookedAt.toString()); put("bookingOrder", conflict.bookingOrder)
                    } })
                    assertEquals(expected, details.getValue("overlaps"))
                    assertFalse(response.containsKey("booking"))
                    assertEquals(before, f.snapshot())
                    return details
                }
                assertEquals(warning(2), warning(2))
                f.revision(candidate, 3)
                val beforeStale = f.snapshot()
                val stale = http.post(candidate, key, "false", 2, 409)
                assertEquals("STALE_STATE", stale.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(beforeStale, f.snapshot())
                warning(3)
                val created = http.post(candidate, key, "true", 3, 201)
                assertEquals("booked", created.getValue("status").jsonPrimitive.content)
                assertEquals(4L, created.getValue("revision").jsonPrimitive.long)
                assertEquals(listOf(first.bookingId.toString(), second.bookingId.toString()),
                    created.getValue("booking").jsonObject.getValue("overlappingActive").jsonArray.map { it.jsonObject.getValue("bookingId").jsonPrimitive.content })
                val committed = f.snapshot()
                assertEquals(created, http.post(candidate, key, "true", 3, 201))
                assertEquals(committed, f.snapshot())
            }
        }
    }

    @Test
    fun `confirmOverlap requires native Boolean and malformed values have no effects`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.seedBooking(f.chats[0], 10, 20)
            val candidate = f.proposal(f.chats[1], 12, 18)
            Http(f).use { http ->
                for (invalid in listOf("\"true\"", "\"false\"", "null", "0", "1", "{}", "[]", null)) {
                    val before = f.snapshot()
                    val response = http.post(candidate, UUID.randomUUID(), invalid, 2, 422)
                    assertEquals("INVALID_FIELD", response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                    assertEquals(before, f.snapshot(), "Malformed confirmOverlap must have no effects")
                }
            }
        }
    }

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
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_overlap_warning$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-overlap-query-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val service = BookingService(runtime)
        val actor = UUID.randomUUID()
        private val peer = UUID.randomUUID()
        private val points = List(2) { UUID.randomUUID() }
        val chats = List(3) { UUID.randomUUID() }
        private val start = Instant.parse("2030-01-01T12:00:00Z")
        private val bookedAt = Instant.parse("2026-01-01T00:00:00Z")

        private val session = UUID.randomUUID()
        fun bearer(): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0, now.minusSeconds(1), now.plusSeconds(3600))
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

        private fun connection(): Connection = DriverManager.getConnection(url, user, password)
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

        fun snapshot(): List<List<List<String?>>> = connection().use { c ->
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
            queries.mapIndexed { index, query ->
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
