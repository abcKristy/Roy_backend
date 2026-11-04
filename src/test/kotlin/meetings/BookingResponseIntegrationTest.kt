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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class BookingResponseIntegrationTest {
    @Test
    fun `canonical booking snapshots keep actual owner identity order and only direct active overlaps`() = runBlocking<Unit> {
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
                fun request(actor: UUID, path: String, body: JsonObject? = null, key: UUID? = null): JsonObject {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", f.bearer(actor))
                    val command = if (body == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                        .header("Idempotency-Key", checkNotNull(key).toString()).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
                    val response = client.send(command, HttpResponse.BodyHandlers.ofString())
                    assertEquals(if (body == null) 200 else 201, response.statusCode())
                    return Json.parseToJsonElement(response.body()).jsonObject
                }
                val historyBefore = f.bookingRow(f.oldTargetBooking)
                val cancelledBefore = request(f.booker, "/api/v1/proposals/${f.cancelled}")
                assertEquals("ready_to_book", cancelledBefore["status"]!!.jsonPrimitive.content)
                val history = cancelledBefore["booking"]!!.jsonObject
                assertEquals("cancelled", history["status"]!!.jsonPrimitive.content)
                assertEquals(f.cancelledBooking.toString(), history["id"]!!.jsonPrimitive.content)
                assertEquals("insufficient_votes", history["cancelReason"]!!.jsonPrimitive.content)
                assertNotEquals(JsonNull, history["cancelledAt"])
                assertTrue(history["overlappingActive"]!!.jsonArray.isEmpty())

                val key = UUID.randomUUID()
                val body = buildJsonObject { put("confirmOverlap", true); put("expectedRevision", 2) }
                val created = request(f.booker, "/api/v1/proposals/${f.target}/booking", body, key)
                assertEquals(setOf("id", "chatId", "proposedByUserId", "startsAt", "endsAt", "status", "goingCount", "viewerGoing", "booking", "revision", "createdAt", "updatedAt"), created.keys)
                assertEquals(f.target.toString(), created["id"]!!.jsonPrimitive.content)
                assertEquals(f.proposer.toString(), created["proposedByUserId"]!!.jsonPrimitive.content)
                assertEquals("booked", created["status"]!!.jsonPrimitive.content)
                assertEquals(2, created["goingCount"]!!.jsonPrimitive.int)
                assertEquals(true, created["viewerGoing"]!!.jsonPrimitive.boolean)
                assertEquals(3L, created["revision"]!!.jsonPrimitive.long)
                val booking = created["booking"]!!.jsonObject
                assertEquals(setOf("id", "proposalId", "bookedByUserId", "status", "bookingOrder", "overlappingActive", "bookedAt", "cancelledAt", "cancelReason"), booking.keys)
                assertEquals(f.target.toString(), booking["proposalId"]!!.jsonPrimitive.content)
                assertEquals(f.booker.toString(), booking["bookedByUserId"]!!.jsonPrimitive.content)
                assertNotEquals(f.proposer, f.booker)
                assertEquals("active", booking["status"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, booking["cancelledAt"]); assertEquals(JsonNull, booking["cancelReason"])
                val bookingId = UUID.fromString(booking["id"]!!.jsonPrimitive.content)
                assertNotEquals(f.oldTargetBooking, bookingId)
                val conflicts = booking["overlappingActive"]!!.jsonArray
                // Insert order is deliberately opposite bookedAt/start order.
                val expected = listOf(f.conflict(f.directLaterBooking), f.conflict(f.directEarlierBooking))
                assertEquals(expected, conflicts.toList())
                assertTrue(expected[0]["bookingOrder"]!!.jsonPrimitive.long < expected[1]["bookingOrder"]!!.jsonPrimitive.long)
                assertTrue(Instant.parse(expected[0]["bookedAt"]!!.jsonPrimitive.content).isAfter(Instant.parse(expected[1]["bookedAt"]!!.jsonPrimitive.content)))
                for (conflict in conflicts) assertEquals(setOf("bookingId", "proposalId", "startsAt", "endsAt", "bookedAt", "bookingOrder"), conflict.jsonObject.keys)
                assertTrue(booking["bookingOrder"]!!.jsonPrimitive.long > historyBefore["booking_order"]!!.jsonPrimitive.long)
                assertEquals(historyBefore, f.bookingRow(f.oldTargetBooking))
                assertEquals(created, request(f.booker, "/api/v1/proposals/${f.target}"))
                val list = request(f.booker, "/api/v1/chats/${f.chats[0]}/proposals")["items"]!!.jsonArray
                assertEquals(created, list.single { it.jsonObject["id"] == created["id"] })
                assertEquals(cancelledBefore, list.single { it.jsonObject["id"] == cancelledBefore["id"] })
                val personal = request(f.observer, "/api/v1/proposals/${f.target}")
                assertEquals(JsonObject(created.toMutableMap().apply { put("viewerGoing", JsonPrimitive(false)) }), personal)
                assertEquals(booking, personal["booking"])
                val observerList = request(f.observer, "/api/v1/chats/${f.chats[0]}/proposals")["items"]!!.jsonArray
                assertEquals(personal, observerList.single { it.jsonObject["id"] == personal["id"] })
                assertEquals(booking["bookingOrder"], f.bookingRow(bookingId)["booking_order"])
                val stable = f.state()
                repeat(2) {
                    assertEquals(created, request(f.booker, "/api/v1/proposals/${f.target}/booking", body, key))
                    assertEquals(created, request(f.booker, "/api/v1/proposals/${f.target}"))
                    assertEquals(cancelledBefore, request(f.booker, "/api/v1/proposals/${f.cancelled}"))
                }
                assertEquals(stable, f.state())
                assertEquals(historyBefore, f.bookingRow(f.oldTargetBooking))
            } finally { server.stop(0, 1000) }
        }
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_booking_response$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-booking-response-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val proposer = UUID.randomUUID(); val booker = UUID.randomUUID(); val observer = UUID.randomUUID()
        private val people = listOf(proposer, booker, observer)
        private val sessions = people.associateWith { UUID.randomUUID() }
        private val points = List(2) { UUID.randomUUID() }
        val chats = List(3) { UUID.randomUUID() }
        val target = UUID.randomUUID(); private val directLater = UUID.randomUUID(); private val directEarlier = UUID.randomUUID()
        private val transitive = UUID.randomUUID(); private val touching = UUID.randomUUID(); val cancelled = UUID.randomUUID()
        private val otherPoint = UUID.randomUUID()
        val directLaterBooking = UUID.randomUUID(); val directEarlierBooking = UUID.randomUUID()
        val cancelledBooking = UUID.randomUUID(); val oldTargetBooking = UUID.randomUUID()
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Response fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    for (point in points) execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic response fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, proposer)
                    for ((index, chat) in chats.withIndex()) {
                        val topic = UUID.randomUUID()
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())", topic,
                            if (index < 2) points[0] else points[1], if (index == 1) "basketball" else "football")
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                        for (id in people) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    }
                    val now = c.createStatement().use { s -> s.executeQuery("SELECT date_trunc('second',clock_timestamp())").use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant() } }
                    val base = now.plusSeconds(86400)
                    val slots = listOf(Slot(target, chats[0], 0, 3600), Slot(directLater, chats[0], 2700, 5400),
                        Slot(directEarlier, chats[1], -1800, 1800), Slot(transitive, chats[1], 4500, 7200),
                        Slot(touching, chats[1], 3600, 7200), Slot(cancelled, chats[0], 900, 2700), Slot(otherPoint, chats[2], 0, 3600))
                    for (slot in slots) {
                        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                            VALUES (?,?,?,?,?,2,clock_timestamp(),clock_timestamp())""", slot.id, slot.chat, proposer,
                            base.plusSeconds(slot.start).atOffset(ZoneOffset.UTC), base.plusSeconds(slot.end).atOffset(ZoneOffset.UTC))
                        for (id in listOf(proposer, booker)) execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", slot.id, id)
                    }
                    fun booking(id: UUID, proposal: UUID, at: Instant, cancelled: Boolean = false) {
                        execute(c, """INSERT INTO bookings(id,proposal_id,booked_by_user_id,status,created_at,cancelled_at,cancel_reason)
                            VALUES (?,?,?, ?,?,?,?)""", id, proposal, proposer, if (cancelled) "CANCELLED" else "ACTIVE", at.atOffset(ZoneOffset.UTC),
                            if (cancelled) now.atOffset(ZoneOffset.UTC) else null, if (cancelled) "INSUFFICIENT_VOTES" else null)
                    }
                    booking(directLaterBooking, directLater, now.plusSeconds(300))
                    booking(directEarlierBooking, directEarlier, now.minusSeconds(300))
                    booking(UUID.randomUUID(), transitive, now)
                    booking(UUID.randomUUID(), touching, now)
                    booking(UUID.randomUUID(), otherPoint, now)
                    booking(cancelledBooking, cancelled, now.minusSeconds(600), true)
                    booking(oldTargetBooking, target, now.minusSeconds(600), true)
                    c.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }
        private data class Slot(val id: UUID, val chat: UUID, val start: Long, val end: Long)
        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun bearer(actor: UUID): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, sessions.getValue(actor), 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun bookingRow(id: UUID): JsonObject = connection().use { c ->
            c.prepareStatement("SELECT row_to_json(b)::text FROM bookings b WHERE id=?").use { s ->
                s.setObject(1, id); s.executeQuery().use { r -> check(r.next()); Json.parseToJsonElement(r.getString(1)).jsonObject }
            }
        }
        fun conflict(id: UUID): JsonObject = connection().use { c ->
            c.prepareStatement("SELECT b.id,b.proposal_id,p.starts_at,p.ends_at,b.created_at,b.booking_order FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE b.id=?").use { s ->
                s.setObject(1, id); s.executeQuery().use { r -> check(r.next()); buildJsonObject {
                    put("bookingId", r.getObject(1).toString()); put("proposalId", r.getObject(2).toString())
                    put("startsAt", r.getObject(3, OffsetDateTime::class.java).toInstant().toString())
                    put("endsAt", r.getObject(4, OffsetDateTime::class.java).toInstant().toString())
                    put("bookedAt", r.getObject(5, OffsetDateTime::class.java).toInstant().toString()); put("bookingOrder", r.getLong(6))
                } }
            }
        }
        fun state(): List<List<String?>> = connection().use { c ->
            c.prepareStatement("""SELECT b.id::text,b.booking_order::text,b.status,b.booked_by_user_id::text FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id IN (?,?,?)
                UNION ALL SELECT p.id::text,p.revision::text,p.lifecycle,p.updated_at::text FROM meeting_proposals p WHERE p.chat_id IN (?,?,?)
                UNION ALL SELECT m.id::text,m.message_seq::text,m.system_type,m.system_payload::text FROM messages m WHERE m.chat_id IN (?,?,?)
                UNION ALL SELECT e.id::text,e.recipient_user_id::text,e.event_type,e.dedupe_key FROM notification_events e WHERE e.point_id IN (?,?)
                UNION ALL SELECT actor_user_id::text,idempotency_key,operation_type,response_snapshot::text FROM operation_idempotency WHERE actor_user_id=?
                UNION ALL SELECT id::text,updated_at::text,address,address_source FROM points WHERE id IN (?,?) ORDER BY 1""").use { s ->
                (chats + chats + chats + points + listOf(booker) + points).forEachIndexed { index, id -> s.setObject(index + 1, id) }
                s.executeQuery().use { r -> buildList { while (r.next()) add((1..4).map { r.getString(it) }) } }
            }
        }
        override fun close() {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (point in points) execute(c, "DELETE FROM points WHERE id=?", point)
                    for (id in people) execute(c, "DELETE FROM users WHERE id=?", id)
                    c.commit()
                }
            } finally { runtime.close() }
        }
    }
    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any?) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}