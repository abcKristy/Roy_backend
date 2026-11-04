package com.example.meetings

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

class BookingSchemaIntegrationTest {
    @Test
    fun `schema permits only one active booking per proposal and preserves cancelled rows on rebooking`() {
        val url = testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m4_booking_schema$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "m4-booking-schema-test-secret-at-least-32-bytes", appEnvironment = AppEnvironment.LOCAL)).start().use {
            DriverManager.getConnection(url, user, password).use { c ->
                c.autoCommit = false
                try {
                    val creator = UUID.randomUUID(); val owner = UUID.randomUUID()
                    val point = UUID.randomUUID(); val topic = UUID.randomUUID(); val chat = UUID.randomUUID()
                    val proposal = UUID.randomUUID(); val otherProposal = UUID.randomUUID()
                    for (id in listOf(creator, owner)) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Booking schema fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic booking schema fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, creator)
                    execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in listOf(proposal, otherProposal)) execute(c,
                        """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                        VALUES (?,?,?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '25 hours',clock_timestamp(),clock_timestamp())""", id, chat, creator)
                    expectSqlState(c, "23503") { insert(c, UUID.randomUUID(), UUID.randomUUID(), owner) }
                    expectSqlState(c, "23503") { insert(c, UUID.randomUUID(), proposal, UUID.randomUUID()) }
                    expectSqlState(c, "23502") { insert(c, UUID.randomUUID(), null, owner) }
                    // The database assigns order; callers cannot submit an arbitrary sequence value.
                    expectSqlState(c, "428C9") {
                        execute(c, "INSERT INTO bookings(id,booking_order,proposal_id,booked_by_user_id,created_at) VALUES (?,123,?,?,clock_timestamp())",
                            UUID.randomUUID(), proposal, owner)
                    }
                    val firstId = UUID.randomUUID()
                    insert(c, firstId, proposal, owner)
                    val first = row(c, firstId)
                    assertEquals(proposal, first.proposal)
                    assertEquals(owner, first.owner)
                    assertEquals("ACTIVE", first.status)
                    assertTrue(first.order > 0)
                    assertNull(first.cancelledAt); assertNull(first.reason)
                    expectSqlState(c, "23505") { insert(c, UUID.randomUUID(), proposal, creator) }
                    assertEquals(first, row(c, firstId))
                    // The partial index is scoped to a proposal, not a global time/point exclusion.
                    val independentId = UUID.randomUUID()
                    insert(c, independentId, otherProposal, creator)
                    assertEquals("ACTIVE", row(c, independentId).status)
                    for (sql in listOf(
                        "UPDATE bookings SET status='UNKNOWN' WHERE id=?",
                        "UPDATE bookings SET cancelled_at=clock_timestamp() WHERE id=?",
                        "UPDATE bookings SET cancel_reason='INSUFFICIENT_VOTES' WHERE id=?",
                        "UPDATE bookings SET status='CANCELLED' WHERE id=?",
                        "UPDATE bookings SET status='CANCELLED',cancelled_at=clock_timestamp() WHERE id=?",
                        "UPDATE bookings SET status='CANCELLED',cancelled_at=clock_timestamp(),cancel_reason='UNKNOWN' WHERE id=?",
                    )) expectSqlState(c, "23514") { execute(c, sql, firstId) }
                    execute(c, "UPDATE bookings SET status='CANCELLED',cancelled_at=clock_timestamp(),cancel_reason='INSUFFICIENT_VOTES' WHERE id=?", firstId)
                    val cancelled = row(c, firstId)
                    assertEquals(first.copy(status = "CANCELLED", cancelledAt = cancelled.cancelledAt, reason = "INSUFFICIENT_VOTES"), cancelled)
                    assertNotNull(cancelled.cancelledAt)
                    val replacementId = UUID.randomUUID()
                    insert(c, replacementId, proposal, creator)
                    val replacement = row(c, replacementId)
                    assertNotEquals(cancelled.id, replacement.id)
                    assertTrue(replacement.order > cancelled.order, "Identity gaps are valid; only monotonic new order is required")
                    assertEquals("ACTIVE", replacement.status)
                    assertEquals(creator, replacement.owner)
                    assertEquals(cancelled, row(c, firstId), "Rebooking must preserve the old cancelled row")
                    expectSqlState(c, "23505") { insert(c, UUID.randomUUID(), proposal, owner) }
                    c.prepareStatement("SELECT count(*),count(*) FILTER(WHERE status='ACTIVE'),count(*) FILTER(WHERE status='CANCELLED') FROM bookings WHERE proposal_id=?").use { s ->
                        s.setObject(1, proposal)
                        s.executeQuery().use { r -> check(r.next()); assertEquals(listOf(2L, 1L, 1L), listOf(r.getLong(1), r.getLong(2), r.getLong(3))) }
                    }
                    // Historical ownership becomes nullable when an account is deleted, without losing the booking.
                    execute(c, "DELETE FROM users WHERE id=?", owner)
                    assertEquals(cancelled.copy(owner = null), row(c, firstId))
                    assertEquals(replacement, row(c, replacementId))
                    execute(c, "DELETE FROM meeting_proposals WHERE id=?", proposal)
                    c.prepareStatement("SELECT count(*) FROM bookings WHERE proposal_id=?").use { s ->
                        s.setObject(1, proposal); s.executeQuery().use { r -> check(r.next()); assertEquals(0, r.getInt(1)) }
                    }
                    assertEquals(otherProposal, row(c, independentId).proposal)
                } finally { c.rollback() }
            }
        }
    }

    private data class BookingRow(val id: UUID, val proposal: UUID, val owner: UUID?, val order: Long,
        val status: String, val createdAt: Instant, val cancelledAt: Instant?, val reason: String?)
    private fun row(c: Connection, id: UUID): BookingRow = c.prepareStatement("SELECT id,proposal_id,booked_by_user_id,booking_order,status,created_at,cancelled_at,cancel_reason FROM bookings WHERE id=?").use { s ->
        s.setObject(1, id)
        s.executeQuery().use { r ->
            check(r.next())
            BookingRow(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getObject(3, UUID::class.java),
                r.getLong(4), r.getString(5), r.getObject(6, OffsetDateTime::class.java).toInstant(),
                r.getObject(7, OffsetDateTime::class.java)?.toInstant(), r.getString(8))
        }
    }
    private fun insert(c: Connection, id: UUID, proposal: UUID?, owner: UUID?) = execute(c,
        "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())", id, proposal, owner)
    private fun expectSqlState(c: Connection, sqlState: String, block: () -> Unit) {
        val savepoint = c.setSavepoint()
        try { assertEquals(sqlState, assertFailsWith<SQLException> { block() }.sqlState) }
        finally { c.rollback(savepoint); c.releaseSavepoint(savepoint) }
    }
    private fun execute(c: Connection, sql: String, vararg values: Any?) {
        c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
    }
}
