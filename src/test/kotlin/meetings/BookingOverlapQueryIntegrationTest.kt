package com.example.meetings

import com.example.api.proposalJson
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class BookingOverlapQueryIntegrationTest {
    @Test
    fun `same point all topic overlaps use strict half open boundaries and active rows only`() = runBlocking<Unit> {
        Fixture().use { f ->
            val first = f.seedBooking(f.chats[0], 10, 20)
            val second = f.seedBooking(f.chats[1], 10, 20)
            val cancelled = f.seedBooking(f.chats[0], 0, 40, cancelled = true)
            val elsewhere = f.seedBooking(f.chats[2], 0, 40)
            assertTrue(first.bookingOrder < second.bookingOrder)
            assertEquals(first.bookedAt, second.bookedAt)
            val expected = listOf(first, second)
            val cases = listOf(
                Window("adjacent before", 0, 10, false),
                Window("adjacent after", 20, 30, false),
                Window("disjoint", 30, 35, false),
                Window("partial left", 5, 15, true),
                Window("partial right", 15, 25, true),
                Window("inside", 12, 18, true),
                Window("contains", 5, 25, true),
                Window("equal", 10, 20, true),
            )
            for (window in cases) {
                val candidate = f.proposal(f.chats[1], window.start, window.end)
                val command = BookMeetingCommand(f.actor, candidate, UUID.randomUUID(), false, 2)
                val before = f.snapshot()
                val result = f.service.book(command) { _, value -> proposalJson(value) }
                if (window.overlaps) {
                    val warning = assertIs<BookMeetingResult.OverlapConfirmRequired>(result, window.name)
                    assertEquals(expected, warning.overlaps, window.name)
                    assertFalse(warning.overlaps.any { it.bookingId in setOf(cancelled.bookingId, elsewhere.bookingId) }, window.name)
                    assertEquals(before, f.snapshot(), "Query refusal must be effect-free: ${window.name}")
                    val repeated = assertIs<BookMeetingResult.OverlapConfirmRequired>(
                        f.service.book(command) { _, _ -> error("Overlap query must not create a booking") })
                    assertEquals(warning, repeated, window.name)
                    assertEquals(before, f.snapshot(), "Repeated query must be effect-free: ${window.name}")
                } else {
                    val created = assertIs<BookMeetingResult.Created>(result, window.name)
                    assertTrue(assertNotNull(created.proposal.booking).overlappingActive.isEmpty(), window.name)
                    assertEquals(candidate, created.proposal.id)
                }
                f.deleteCandidate(candidate)
            }
        }
    }

    @Test
    fun `direct overlap query does not add transitive A through B to C`() = runBlocking<Unit> {
        Fixture().use { f ->
            val a = f.seedBooking(f.chats[0], 0, 10)
            val b = f.seedBooking(f.chats[1], 5, 15)
            // A intersects B, B intersects C, but A.end == C.start.
            val c = f.proposal(f.chats[0], 10, 20)
            val before = f.snapshot()
            val result = assertIs<BookMeetingResult.OverlapConfirmRequired>(f.service.book(
                BookMeetingCommand(f.actor, c, UUID.randomUUID(), false, 2)) { _, _ -> error("C needs an overlap warning") })
            assertEquals(listOf(b), result.overlaps)
            assertFalse(result.overlaps.any { it.bookingId == a.bookingId })
            assertEquals(before, f.snapshot())
            // Querying the A interval independently observes B directly (and the existing A row).
            val sameAsA = f.proposal(f.chats[0], 0, 10)
            val aBefore = f.snapshot()
            val aQuery = assertIs<BookMeetingResult.OverlapConfirmRequired>(f.service.book(
                BookMeetingCommand(f.actor, sameAsA, UUID.randomUUID(), false, 2)) { _, _ -> error("A window overlaps") })
            assertEquals(listOf(a, b), aQuery.overlaps)
            assertEquals(aBefore, f.snapshot())
        }
    }

    private data class Window(val name: String, val start: Long, val end: Long, val overlaps: Boolean)

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_overlap_query$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        private val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "m4-overlap-query-secret-at-least-32-bytes", appEnvironment = AppEnvironment.LOCAL)).start()
        val service = BookingService(runtime)
        val actor = UUID.randomUUID()
        private val peer = UUID.randomUUID()
        private val points = List(2) { UUID.randomUUID() }
        val chats = List(3) { UUID.randomUUID() }
        private val start = Instant.parse("2030-01-01T12:00:00Z")
        private val bookedAt = Instant.parse("2026-01-01T00:00:00Z")

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
