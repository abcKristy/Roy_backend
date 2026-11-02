package com.example.meetings

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookingServiceIntegrationTest {
    @Test
    fun `booking requires votes and explicit overlap confirmation then close cancels only its booking`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step6booking$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val owner = UUID.randomUUID()
        val guest = UUID.randomUUID()
        val point = UUID.randomUUID()
        val firstChat = UUID.randomUUID()
        val secondChat = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val start = Instant.now().plusSeconds(86_400)
        val end = start.plusSeconds(5_400)
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            insertUser(connection, owner)
            insertUser(connection, guest)
            connection.prepareStatement(
                """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, point); statement.setObject(2, owner); statement.executeUpdate() }
            insertChat(connection, point, firstChat, owner, guest, "football")
            insertChat(connection, point, secondChat, owner, guest, "basketball")
            insertProposal(connection, first, firstChat, owner, start, end)
            insertProposal(connection, second, secondChat, owner, start.plusSeconds(3_600), end.plusSeconds(3_600))
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "booking-service-test-secret-at-least-32-bytes", appEnvironment = AppEnvironment.PRODUCTION)).start()
        try {
            val service = BookingService(runtime)
            fun book(id: UUID, key: UUID, overlap: Boolean, revision: Long) =
                BookMeetingCommand(owner, id, key, overlap, revision)
            val body: (Connection, ProposalView) -> kotlinx.serialization.json.JsonObject = { _, view ->
                buildJsonObject { put("id", view.id.toString()); put("revision", view.revision) }
            }
            assertEquals(BookMeetingResult.VoteRequired,
                service.book(book(first, UUID.randomUUID(), false, 0), body))
            DriverManager.getConnection(url, user, password).use { connection ->
                insertVote(connection, first, owner)
            }
            assertEquals(BookMeetingResult.InsufficientVotes,
                service.book(book(first, UUID.randomUUID(), false, 0), body))
            DriverManager.getConnection(url, user, password).use { connection ->
                insertVote(connection, first, guest)
                insertVote(connection, second, owner)
                insertVote(connection, second, guest)
            }
            assertEquals(BookMeetingResult.Stale,
                service.book(book(first, UUID.randomUUID(), false, 99), body))

            val firstKey = UUID.randomUUID()
            val firstBooked = assertIs<BookMeetingResult.Created>(service.book(book(first, firstKey, false, 0), body))
            assertEquals("booked", firstBooked.proposal.status)
            assertEquals(1L, firstBooked.proposal.revision)
            assertEquals(1, firstBooked.systemMessages.size)
            assertEquals(firstBooked.responseSnapshot,
                assertIs<BookMeetingResult.Replay>(service.book(book(first, firstKey, false, 0), body)).responseSnapshot)
            assertEquals(BookMeetingResult.AlreadyBooked,
                service.book(book(first, UUID.randomUUID(), false, 1), body))

            val secondKey = UUID.randomUUID()
            val confirmation = assertIs<BookMeetingResult.OverlapConfirmRequired>(
                service.book(book(second, secondKey, false, 0), body))
            assertEquals(firstBooked.bookingChange.bookingId, confirmation.overlaps.single().bookingId)
            val secondBooked = assertIs<BookMeetingResult.Created>(service.book(book(second, secondKey, true, 0), body))
            assertTrue(secondBooked.proposal.booking!!.bookingOrder > firstBooked.proposal.booking!!.bookingOrder)
            assertEquals(firstBooked.bookingChange.bookingId,
                secondBooked.proposal.booking!!.overlappingActive.single().bookingId)

            val closeKey = UUID.randomUUID()
            val closed = assertIs<CloseMeetingResult.Created>(service.close(
                CloseMeetingCommand(owner, second, closeKey, 1), body))
            assertEquals("closed", closed.proposal.status)
            assertEquals(2L, closed.proposal.revision)
            assertEquals("cancelled", closed.bookingChange?.status)
            assertEquals("closed_by_author", closed.bookingChange?.cancelReason)
            assertEquals(2, closed.systemMessages.size)
            assertEquals(closed.responseSnapshot,
                assertIs<CloseMeetingResult.Replay>(service.close(
                    CloseMeetingCommand(owner, second, closeKey, 1), body)).responseSnapshot)
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement("SELECT status,cancel_reason FROM bookings WHERE id=?").use { statement ->
                    statement.setObject(1, secondBooked.bookingChange.bookingId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals("CANCELLED", rows.getString(1))
                        assertEquals("CLOSED_BY_AUTHOR", rows.getString(2))
                    }
                }
                connection.prepareStatement("SELECT count(*) FROM bookings WHERE proposal_id=? AND status='ACTIVE'")
                    .use { statement ->
                        statement.setObject(1, first)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
                    }
                connection.prepareStatement("SELECT count(*) FROM notification_events WHERE booking_id=?")
                    .use { statement ->
                        statement.setObject(1, secondBooked.bookingChange.bookingId)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertTrue(rows.getInt(1) >= 2) }
                    }
            }
        } finally {
            runtime.close()
        }
    }

    private fun insertUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, id); statement.executeUpdate() }
    }

    private fun insertChat(connection: Connection, point: UUID, chat: UUID, owner: UUID, guest: UUID, preset: String) {
        val topic = UUID.randomUUID()
        connection.prepareStatement("INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())")
            .use { statement ->
                statement.setObject(1, topic); statement.setObject(2, point); statement.setString(3, preset)
                statement.executeUpdate()
            }
        connection.prepareStatement("INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())")
            .use { statement -> statement.setObject(1, chat); statement.setObject(2, topic); statement.executeUpdate() }
        listOf(owner, guest).forEach { person ->
            connection.prepareStatement("INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())")
                .use { statement -> statement.setObject(1, chat); statement.setObject(2, person); statement.executeUpdate() }
        }
    }

    private fun insertProposal(connection: Connection, id: UUID, chat: UUID, owner: UUID, start: Instant, end: Instant) {
        connection.prepareStatement(
            """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
               VALUES (?,?,?,?,?,clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id); statement.setObject(2, chat); statement.setObject(3, owner)
            statement.setObject(4, start.atOffset(ZoneOffset.UTC)); statement.setObject(5, end.atOffset(ZoneOffset.UTC))
            statement.executeUpdate()
        }
    }

    private fun insertVote(connection: Connection, proposal: UUID, person: UUID) {
        connection.prepareStatement("INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())")
            .use { statement -> statement.setObject(1, proposal); statement.setObject(2, person); statement.executeUpdate() }
    }
}
