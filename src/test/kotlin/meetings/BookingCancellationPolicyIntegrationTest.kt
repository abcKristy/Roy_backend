package com.example.meetings

import com.example.accounts.AccountDeletionResult
import com.example.accounts.AccountDeletionService
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionIdentity
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.LeavePointChatResult
import com.example.points.PointWriteService
import com.example.testing.testDatabaseUrl
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class BookingCancellationPolicyIntegrationTest {
    @Test
    fun `leave after start cancels two to one but preserves three to two and unrelated history`() = runBlocking<Unit> {
        Fixture(started = true).use { f ->
            val before = f.bookings()
            assertEquals("booked", assertNotNull(f.proposals.detail(f.fragile, f.b)).status)
            assertEquals(VoteResult.Started, f.proposals.vote(VoteCommand(f.b, f.fragile, false)))
            assertEquals(before, f.bookings(), "Time and denied late PUT alone must not cancel booked history")
            val result = assertIs<LeavePointChatResult.Left>(PointWriteService(f.runtime).leave(f.a, f.chat))
            assertFalse(result.pointDeleted)
            f.assertSettled(before, deletedActor = false)
            assertEquals(listOf(f.fragile), result.bookingChanges.map { it.proposalId })
            val settled = f.bookings()
            val messages = f.cancellationMessages()
            assertEquals(LeavePointChatResult.AlreadyLeft, PointWriteService(f.runtime).leave(f.a, f.chat))
            assertEquals(settled, f.bookings())
            assertEquals(messages, f.cancellationMessages())
        }
    }

    @Test
    fun `account deletion after start applies the same vote loss policy and preserves shared points`() = runBlocking<Unit> {
        Fixture(started = true).use { f ->
            val before = f.bookings()
            assertEquals("booked", assertNotNull(f.proposals.detail(f.fragile, f.b)).status)
            val deletion = AccountDeletionService(f.runtime, PasswordHashingWork(f.hasher))
            val result = assertIs<AccountDeletionResult.Deleted>(deletion.delete(f.identity, Fixture.PASSWORD))
            assertTrue(result.removedPoints.isEmpty())
            f.assertSettled(before, deletedActor = true)
            assertEquals(listOf(f.fragile), result.meetingChanges.mapNotNull { it.bookingChange?.let { _ -> it.proposal.id } })
            val settled = f.bookings()
            val messages = f.cancellationMessages()
            assertEquals(AccountDeletionResult.SessionExpired, deletion.delete(f.identity, Fixture.PASSWORD))
            assertEquals(settled, f.bookings())
            assertEquals(messages, f.cancellationMessages())
        }
    }

    @Test
    fun `future desired vote removal uses the same two to one and three to two policy once`() = runBlocking<Unit> {
        Fixture(started = false).use { f ->
            val before = f.bookings()
            assertIs<VoteResult.Updated>(f.proposals.vote(VoteCommand(f.a, f.fragile, false)))
            assertIs<VoteResult.Updated>(f.proposals.vote(VoteCommand(f.a, f.stable, false)))
            f.assertSettled(before, deletedActor = false)
            val settled = f.bookings()
            assertIs<VoteResult.Unchanged>(f.proposals.vote(VoteCommand(f.a, f.fragile, false)))
            assertEquals(settled, f.bookings())
            f.connection().use { connection ->
                connection.autoCommit = false
                assertTrue(BookingCancellationPolicy().cancelInsufficientBookings(connection, listOf(f.fragile, f.stable)).isEmpty())
                connection.commit()
            }
            assertEquals(settled, f.bookings(), "Repeated recalculation must preserve cancellation timestamp and history")
            assertEquals(1, f.cancellationMessages())
        }
    }

    private data class BookingRow(val id: UUID, val proposal: UUID, val owner: UUID?, val order: Long,
        val status: String, val createdAt: Instant, val cancelledAt: Instant?, val reason: String?)

    private class Fixture(started: Boolean) : AutoCloseable {
        private val url = testDatabaseUrl().also {
            require(Regex("^jdbc:postgresql://[^/]+/roy_m4_cancel_policy$").matches(it))
        }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        private val secret = "m4-cancellation-policy-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        private val people = listOf(a, b, c)
        private val session = UUID.randomUUID()
        val identity = SessionIdentity(a, session, 0)
        val hasher = Argon2idPasswordHasher()
        private val points = List(2) { UUID.randomUUID() }
        val chat = UUID.randomUUID()
        private val foreignChat = UUID.randomUUID()
        val fragile = UUID.randomUUID(); val stable = UUID.randomUUID(); private val foreign = UUID.randomUUID()
        private val proposalIds = listOf(fragile, stable, foreign)
        val proposals = ProposalService(runtime, secret)

        init {
            try {
                connection().use { conn ->
                    conn.autoCommit = false
                    val hash = hasher.hash(PASSWORD)
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(conn, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,?,'Cancellation fixture',20,'test',clock_timestamp(),clock_timestamp()-interval '1 day',clock_timestamp())""", id, phone, hash)
                        execute(conn, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(conn, """INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                        VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)""", session, a, session.toString().replace("-", "").repeat(2))
                    for ((index, point) in points.withIndex()) {
                        execute(conn, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                            VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic cancellation policy point','MANUAL',?,clock_timestamp()-interval '1 day',clock_timestamp())""", point, b)
                        val topic = UUID.randomUUID()
                        val targetChat = if (index == 0) chat else foreignChat
                        execute(conn, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                        execute(conn, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", targetChat, topic)
                        for (actor in if (index == 0) people else listOf(b, c)) {
                            execute(conn, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp()-interval '1 day')", targetChat, actor)
                        }
                    }
                    val now = conn.createStatement().use { s -> s.executeQuery("SELECT date_trunc('second',clock_timestamp())").use { rows ->
                        check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
                    } }
                    val starts = now.plusSeconds(if (started) -7200 else 7200)
                    for (id in proposalIds) {
                        val voters = if (id == fragile) listOf(a, b) else if (id == stable) people else listOf(b, c)
                        val targetChat = if (id == foreign) foreignChat else chat
                        execute(conn, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                            VALUES (?,?,?,?,?,?,?,?)""", id, targetChat, b, starts.atOffset(ZoneOffset.UTC), starts.plusSeconds(3600).atOffset(ZoneOffset.UTC),
                            voters.size.toLong(), now.minusSeconds(18000).atOffset(ZoneOffset.UTC), now.minusSeconds(10800).atOffset(ZoneOffset.UTC))
                        for (actor in voters) execute(conn, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,?)", id, actor, now.minusSeconds(14400).atOffset(ZoneOffset.UTC))
                        execute(conn, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,?)", UUID.randomUUID(), id,
                            if (id == foreign) b else a, now.minusSeconds(10800).atOffset(ZoneOffset.UTC))
                    }
                    conn.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun bookings(): Map<UUID, BookingRow> = connection().use { conn ->
            conn.prepareStatement("""SELECT id,proposal_id,booked_by_user_id,booking_order,status,created_at,cancelled_at,cancel_reason
                FROM bookings WHERE proposal_id=ANY(?)""").use { s ->
                val array = conn.createArrayOf("uuid", proposalIds.toTypedArray())
                try {
                    s.setArray(1, array)
                    s.executeQuery().use { rows -> buildMap {
                        while (rows.next()) {
                            val row = BookingRow(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getObject(3, UUID::class.java),
                                rows.getLong(4), rows.getString(5), rows.getObject(6, OffsetDateTime::class.java).toInstant(),
                                rows.getObject(7, OffsetDateTime::class.java)?.toInstant(), rows.getString(8))
                            put(row.proposal, row)
                        }
                    } }
                } finally { array.free() }
            }
        }
        fun assertSettled(before: Map<UUID, BookingRow>, deletedActor: Boolean) {
            val after = bookings()
            val cancelled = after.getValue(fragile)
            assertEquals("CANCELLED", cancelled.status, "Loss of a real vote must cancel even after startsAt")
            assertEquals("INSUFFICIENT_VOTES", cancelled.reason)
            assertNotNull(cancelled.cancelledAt)
            assertEquals(before.getValue(fragile).copy(owner = if (deletedActor) null else a,
                status = "CANCELLED", cancelledAt = cancelled.cancelledAt, reason = "INSUFFICIENT_VOTES"), cancelled)
            assertEquals(before.getValue(stable).copy(owner = if (deletedActor) null else a), after.getValue(stable))
            assertEquals(before.getValue(foreign), after.getValue(foreign), "Unrelated point must not change")
            connection().use { conn ->
                for ((id, expectedCount, expectedRevision) in listOf(Triple(fragile, 1L, 3L), Triple(stable, 2L, 4L), Triple(foreign, 2L, 2L))) {
                    conn.prepareStatement("SELECT revision,(SELECT count(*) FROM votes WHERE proposal_id=p.id) FROM meeting_proposals p WHERE id=?").use { s ->
                        s.setObject(1, id); s.executeQuery().use { rows -> check(rows.next()); assertEquals(expectedRevision, rows.getLong(1)); assertEquals(expectedCount, rows.getLong(2)) }
                    }
                }
            }
            assertEquals(1, cancellationMessages())
        }
        fun cancellationMessages(): Int = connection().use { conn ->
            conn.prepareStatement("SELECT count(*) FROM messages WHERE system_type='meeting_booking_cancelled' AND chat_id=?").use { s ->
                s.setObject(1, chat); s.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
            }
        }
        override fun close() {
            try {
                connection().use { conn ->
                    conn.autoCommit = false
                    for (id in points) execute(conn, "DELETE FROM points WHERE id=?", id)
                    for (id in people) execute(conn, "DELETE FROM users WHERE id=?", id)
                    conn.commit()
                }
            } finally { runtime.close() }
        }
        companion object { const val PASSWORD = "synthetic-cancellation-password" }
    }
    companion object {
        private fun execute(conn: Connection, sql: String, vararg values: Any?) {
            conn.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}
