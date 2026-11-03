package com.example.meetings

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Booking and vote removal must serialize on the point and proposal. */
class BookingVoteConcurrencyIntegrationTest {
    @Test
    fun `book racing with unvote leaves no active booking with one vote`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step8delete$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val owner = UUID.randomUUID(); val guest = UUID.randomUUID()
        val point = UUID.randomUUID(); val topic = UUID.randomUUID()
        val chat = UUID.randomUUID(); val proposal = UUID.randomUUID()
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "test-secret", appEnvironment = AppEnvironment.LOCAL)).start()
        runtime.use { database ->
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.autoCommit = false
                insertUser(connection, owner); insertUser(connection, guest)
                connection.prepareStatement(
                    """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(37.7,55.8),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
                ).use { s -> s.setObject(1, point); s.setObject(2, owner); s.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
                ).use { s -> s.setObject(1, topic); s.setObject(2, point); s.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())"
                ).use { s -> s.setObject(1, chat); s.setObject(2, topic); s.executeUpdate() }
                for (id in listOf(owner, guest)) {
                    connection.prepareStatement(
                        "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
                    ).use { s -> s.setObject(1, chat); s.setObject(2, id); s.executeUpdate() }
                }
                val startsAt = Instant.now().plusSeconds(86_400)
                connection.prepareStatement(
                    """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                       VALUES (?,?,?,?,?,clock_timestamp(),clock_timestamp())"""
                ).use { s ->
                    s.setObject(1, proposal); s.setObject(2, chat); s.setObject(3, owner)
                    s.setObject(4, startsAt.atOffset(ZoneOffset.UTC))
                    s.setObject(5, startsAt.plusSeconds(3_600).atOffset(ZoneOffset.UTC))
                    s.executeUpdate()
                }
                for (id in listOf(owner, guest)) {
                    connection.prepareStatement(
                        "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())"
                    ).use { s -> s.setObject(1, proposal); s.setObject(2, id); s.executeUpdate() }
                }
                connection.commit()
            }
            val start = CountDownLatch(1)
            val book = async(Dispatchers.IO) {
                start.await()
                BookingService(database).book(
                    BookMeetingCommand(owner, proposal, UUID.randomUUID(), false, 0),
                ) { _, view -> buildJsonObject { put("id", view.id.toString()) } }
            }
            val unvote = async(Dispatchers.IO) {
                start.await()
                ProposalService(database, "booking-vote-concurrency-test-secret-32-bytes")
                    .vote(VoteCommand(guest, proposal, false))
            }
            start.countDown()
            val bookResult = book.await()
            val unvoteResult = unvote.await()
            assertTrue(unvoteResult is VoteResult.Updated)
            assertTrue(bookResult is BookMeetingResult.Created || bookResult is BookMeetingResult.Stale ||
                bookResult is BookMeetingResult.InsufficientVotes)
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    """SELECT (SELECT count(*) FROM votes WHERE proposal_id=?),
                              (SELECT count(*) FROM bookings WHERE proposal_id=? AND status='ACTIVE')"""
                ).use { s ->
                    s.setObject(1, proposal); s.setObject(2, proposal)
                    s.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(0, rows.getInt(2))
                    }
                }
            }
        }
    }

    private fun insertUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'hash','Test',20,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { s -> s.setObject(1, id); s.setString(2, phone); s.executeUpdate() }
        connection.prepareStatement(
            "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())"
        ).use { s -> s.setObject(1, id); s.executeUpdate() }
    }
}
