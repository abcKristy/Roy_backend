package com.example.accounts

import com.example.api.proposalJson
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionIdentity
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.meetings.BookMeetingCommand
import com.example.meetings.BookMeetingResult
import com.example.meetings.BookingService
import com.example.meetings.CloseMeetingCommand
import com.example.meetings.CloseMeetingResult
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountDeletionServiceIntegrationTest {
    @Test
    fun `booking and close replay erase only deleted author references from current and historical snapshots`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step8delete$").matches(url))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val session = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val actorProposal = UUID.randomUUID()
        val peerProposal = UUID.randomUUID()
        val hasher = Argon2idPasswordHasher()
        val hash = hasher.hash("correct-password")
        val config = ServerConfig(url, dbUser, dbPassword, "test-secret", appEnvironment = AppEnvironment.LOCAL)
        HikariDatabaseBootstrap(config).start().use { runtime ->
            try {
                runtime.withJdbcTransaction { connection ->
                    for (id in listOf(actor, peer)) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        connection.execute(
                            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                               VALUES (?,?,?,'Test',20,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())""",
                            id, phone, hash,
                        )
                        connection.execute("INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    connection.execute(
                        """INSERT INTO auth_sessions(id,user_id,created_at,expires_at)
                           VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day')""", session, actor,
                    )
                    connection.execute(
                        """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                           VALUES (?,ST_SetSRID(ST_MakePoint(37.5,55.7),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())""",
                        point, actor,
                    )
                    connection.execute("INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    connection.execute("INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in listOf(actor, peer)) {
                        connection.execute("INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    }
                    for ((proposal, author) in listOf(actorProposal to actor, peerProposal to peer)) {
                        connection.execute(
                            """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                               VALUES (?,?,?,clock_timestamp()+interval '2 hours',clock_timestamp()+interval '3 hours',
                                       clock_timestamp(),clock_timestamp())""", proposal, chat, author,
                        )
                        for (id in listOf(actor, peer)) {
                            connection.execute("INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", proposal, id)
                        }
                    }
                }

                val service = BookingService(runtime)
                val actorBooks = BookMeetingCommand(actor, peerProposal, UUID.randomUUID(), false, 0)
                assertIs<BookMeetingResult.Created>(service.book(actorBooks) { _, view -> proposalJson(view) })
                val close = CloseMeetingCommand(peer, peerProposal, UUID.randomUUID(), 1)
                val closed = assertIs<CloseMeetingResult.Created>(service.close(close) { _, view -> proposalJson(view) })
                val book = BookMeetingCommand(peer, actorProposal, UUID.randomUUID(), false, 0)
                val booked = assertIs<BookMeetingResult.Created>(service.book(book) { _, view -> proposalJson(view) })
                val bookedBody = booked.responseSnapshot.getValue("body").jsonObject
                val closedBody = closed.responseSnapshot.getValue("body").jsonObject
                val closedBooking = closedBody.getValue("booking").jsonObject
                assertEquals(JsonPrimitive(actor.toString()), bookedBody["proposedByUserId"])
                assertEquals(JsonPrimitive(actor.toString()), closedBooking["bookedByUserId"])
                val expectedBooking = booked.responseSnapshot.withField("body", bookedBody.withField("proposedByUserId", JsonNull))
                val expectedClose = closed.responseSnapshot.withField("body", closedBody.withField(
                    "booking", closedBooking.withField("bookedByUserId", JsonNull),
                ))
                suspend fun assertRedactedReplays() {
                    assertEquals(expectedBooking, assertIs<BookMeetingResult.Replay>(service.book(book) { _, _ ->
                        error("Replay should use the stored snapshot")
                    }).responseSnapshot)
                    assertEquals(expectedClose, assertIs<CloseMeetingResult.Replay>(service.close(close) { _, _ ->
                        error("Replay should use the stored snapshot")
                    }).responseSnapshot)
                }

                val deletion = AccountDeletionService(runtime, PasswordHashingWork(hasher))
                assertIs<AccountDeletionResult.Deleted>(deletion.delete(SessionIdentity(actor, session, 0), "correct-password"))
                assertRedactedReplays()

                // Recreate the rows an older release left behind, even after the point is gone.
                val migration = checkNotNull(AccountDeletionServiceIntegrationTest::class.java.getResource(
                    "/db/migration/V017__anonymize_meeting_replay_snapshots.sql",
                )).readText()
                runtime.withJdbcTransaction { connection ->
                    connection.execute("DELETE FROM points WHERE id=?", point)
                    for ((key, snapshot) in listOf(
                        book.idempotencyKey to booked.responseSnapshot,
                        close.idempotencyKey to closed.responseSnapshot,
                    )) {
                        connection.execute(
                            """UPDATE operation_idempotency SET response_snapshot=?::jsonb
                               WHERE actor_user_id=? AND idempotency_key=?""", snapshot.toString(), peer, key.toString(),
                        )
                    }
                    connection.createStatement().use { it.execute(migration) }
                }
                assertRedactedReplays()
            } finally {
                runtime.withJdbcTransaction { connection ->
                    connection.execute("DELETE FROM points WHERE id=?", point)
                    connection.execute("DELETE FROM users WHERE id IN (?,?)", actor, peer)
                }
            }
        }
    }

    @Test
    fun `overlapping deletions anonymize the shared graph and revoke sessions once`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step8delete$").matches(url))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID(); val peer = UUID.randomUUID()
        val phoneBase = (Math.abs(actor.mostSignificantBits % 800_000_000L) + 100_000_000L).toString()
        val session = UUID.randomUUID(); val point = UUID.randomUUID()
        val topic = UUID.randomUUID(); val chat = UUID.randomUUID(); val message = UUID.randomUUID()
        val proposal = UUID.randomUUID(); val booking = UUID.randomUUID()
        val hasher = Argon2idPasswordHasher()
        val hash = hasher.hash("correct-password")
        HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, "test-secret",
            appEnvironment = AppEnvironment.LOCAL)).start().close()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            for ((id, phone) in listOf(actor to "+7$phoneBase" , peer to "+8$phoneBase")) {
                connection.prepareStatement(
                    """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,?,'Test',20,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
                ).use { statement -> statement.setObject(1, id); statement.setString(2, phone)
                    statement.setString(3, hash); statement.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())"
                ).use { statement -> statement.setObject(1, id); statement.executeUpdate() }
            }
            connection.prepareStatement(
                """INSERT INTO auth_sessions(id,user_id,created_at,expires_at)
                   VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day')"""
            ).use { statement -> statement.setObject(1, session); statement.setObject(2, actor); statement.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.5,55.7),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, point); statement.setObject(2, actor); statement.executeUpdate() }
            connection.prepareStatement(
                "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
            ).use { statement -> statement.setObject(1, topic); statement.setObject(2, point); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO chats(id,point_topic_id,last_message_seq,created_at) VALUES (?,?,1,clock_timestamp())").use { statement ->
                statement.setObject(1, chat); statement.setObject(2, topic); statement.executeUpdate() }
            for (id in listOf(actor, peer)) connection.prepareStatement(
                "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
            ).use { statement -> statement.setObject(1, chat); statement.setObject(2, id); statement.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO messages(id,chat_id,message_seq,author_user_id,type,body,created_at)
                   VALUES (?,?,1,?,'TEXT','Hello',clock_timestamp())"""
            ).use { statement -> statement.setObject(1, message); statement.setObject(2, chat)
                statement.setObject(3, actor); statement.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                   VALUES (?,?,?,clock_timestamp()+interval '2 hours',clock_timestamp()+interval '3 hours',
                           clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, proposal); statement.setObject(2, chat)
                statement.setObject(3, actor); statement.executeUpdate() }
            for (id in listOf(actor, peer)) connection.prepareStatement(
                "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())"
            ).use { statement -> statement.setObject(1, proposal); statement.setObject(2, id); statement.executeUpdate() }
            connection.prepareStatement(
                "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())"
            ).use { statement -> statement.setObject(1, booking); statement.setObject(2, proposal)
                statement.setObject(3, actor); statement.executeUpdate() }
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, "test-secret",
            appEnvironment = AppEnvironment.LOCAL)).start()
        runtime.use {
            val service = AccountDeletionService(it, PasswordHashingWork(hasher))
            val identity = SessionIdentity(actor, session, 0)
            assertEquals(AccountDeletionResult.WrongPassword, service.delete(identity, "wrong-password"))
            val bothVerified = CyclicBarrier(2)
            val pausedHasher = object : PasswordHasher by hasher {
                override fun verify(password: String, encodedHash: String): Boolean {
                    val valid = hasher.verify(password, encodedHash)
                    bothVerified.await(10, TimeUnit.SECONDS)
                    return valid
                }
            }
            val concurrentService = AccountDeletionService(it, PasswordHashingWork(pausedHasher))
            val first = async { concurrentService.delete(identity, "correct-password") }
            val second = async { concurrentService.delete(identity, "correct-password") }
            val results = listOf(first.await(), second.await())
            assertIs<AccountDeletionResult.Deleted>(results.single { it is AccountDeletionResult.Deleted })
            assertEquals(1, results.count { it == AccountDeletionResult.SessionExpired })
            assertEquals(AccountDeletionResult.SessionExpired, service.delete(identity, "correct-password"))
        }
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.prepareStatement(
                """SELECT u.deleted_at IS NOT NULL,u.phone_e164,u.password_hash,s.revoked_at IS NOT NULL
                   FROM users u JOIN auth_sessions s ON s.user_id=u.id WHERE u.id=?"""
            ).use { statement -> statement.setObject(1, actor); statement.executeQuery().use { rows ->
                assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); assertEquals(null, rows.getString(2))
                assertEquals(null, rows.getString(3)); assertTrue(rows.getBoolean(4))
            } }
            connection.prepareStatement(
                """SELECT p.created_by_user_id,m.author_user_id,b.booked_by_user_id,b.status,
                          (SELECT count(*) FROM chat_memberships WHERE user_id=?)
                   FROM points p JOIN point_topics pt ON pt.point_id=p.id JOIN chats c ON c.point_topic_id=pt.id
                   JOIN messages m ON m.chat_id=c.id JOIN meeting_proposals mp ON mp.chat_id=c.id
                   JOIN bookings b ON b.proposal_id=mp.id WHERE p.id=?"""
            ).use { statement -> statement.setObject(1, actor); statement.setObject(2, point)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(null, rows.getObject(1)); assertEquals(null, rows.getObject(2))
                    assertEquals(null, rows.getObject(3)); assertEquals("CANCELLED", rows.getString(4))
                    assertEquals(0, rows.getInt(5))
                } }
        }
    }

    private fun Connection.execute(sql: String, vararg values: Any?) {
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
        JsonObject(this + (name to value))
}
