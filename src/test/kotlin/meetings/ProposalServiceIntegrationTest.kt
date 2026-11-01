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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposalServiceIntegrationTest {
    @Test
    fun `proposal creation nearby recipients vote threshold and cancellation are transactional`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step6proposal$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val creator = UUID.randomUUID()
        val voter = UUID.randomUUID()
        val nearby = UUID.randomUUID()
        val pointId = UUID.randomUUID()
        val topicId = UUID.randomUUID()
        val chatId = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            listOf(creator, voter, nearby).forEach { addUser(connection, it) }
            connection.prepareStatement(
                """UPDATE notification_settings SET extra_in_app_enabled=true WHERE user_id=?"""
            ).use { statement -> statement.setObject(1, nearby); statement.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO notification_locations (user_id,position,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.6201,55.7501),4326)::geography,clock_timestamp())"""
            ).use { statement -> statement.setObject(1, nearby); statement.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, pointId); statement.setObject(2, creator); statement.executeUpdate() }
            connection.prepareStatement(
                "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
            ).use { statement -> statement.setObject(1, topicId); statement.setObject(2, pointId); statement.executeUpdate() }
            connection.prepareStatement(
                "INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())"
            ).use { statement -> statement.setObject(1, chatId); statement.setObject(2, topicId); statement.executeUpdate() }
            listOf(creator, voter).forEach { actor ->
                connection.prepareStatement(
                    "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
                ).use { statement -> statement.setObject(1, chatId); statement.setObject(2, actor); statement.executeUpdate() }
            }
            connection.commit()
        }
        val secret = "proposal-integration-secret-at-least-32-bytes"
        val database = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        try {
            val service = ProposalService(database, secret)
            val start = Instant.now().plusSeconds(7_200)
            val command = CreateProposalCommand(creator, chatId, UUID.randomUUID(), start, start.plusSeconds(3_600))
            val created = assertIs<CreateProposalResult.Created>(service.create(command) { _, view ->
                buildJsonObject { put("id", view.id.toString()) }
            })
            assertEquals("voting", created.proposal.status)
            assertEquals(0, created.proposal.goingCount)
            assertEquals(0, created.proposal.revision)
            assertEquals("meeting_proposed", created.emissions.single().draft.kind.databaseValue)
            assertIs<CreateProposalResult.Replay>(service.create(command) { _, view ->
                buildJsonObject { put("id", view.id.toString()) }
            })
            assertIs<CreateProposalResult.KeyReused>(service.create(command.copy(endsAt = start.plusSeconds(5_400))) { _, view ->
                buildJsonObject { put("id", view.id.toString()) }
            })
            assertNull(service.list(chatId, nearby))
            assertEquals(created.proposal.id, service.list(chatId, creator)!!.items.single().id)
            assertNotNull(service.detail(created.proposal.id, voter))
            val first = assertIs<VoteResult.Updated>(service.vote(VoteCommand(creator, created.proposal.id, true)))
            assertEquals(1, first.proposal.goingCount)
            assertTrue(first.emissions.isEmpty())
            assertIs<VoteResult.Unchanged>(service.vote(VoteCommand(creator, created.proposal.id, true)))
            val ready = assertIs<VoteResult.Updated>(service.vote(VoteCommand(voter, created.proposal.id, true)))
            assertEquals("ready_to_book", ready.proposal.status)
            assertEquals(2, ready.proposal.goingCount)
            assertEquals("meeting_ready", ready.emissions.single().draft.kind.databaseValue)
            assertEquals(2, service.votes(created.proposal.id, creator)!!.items.size)

            val bookingId = UUID.randomUUID()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    """INSERT INTO bookings (id,proposal_id,booked_by_user_id,status,created_at)
                       VALUES (?,?,?,'ACTIVE',clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, bookingId)
                    statement.setObject(2, created.proposal.id)
                    statement.setObject(3, creator)
                    statement.executeUpdate()
                }
            }
            assertEquals("active", service.list(chatId, creator)!!.items.single().booking?.status)
            val afterUnvote = assertIs<VoteResult.Updated>(service.vote(VoteCommand(voter, created.proposal.id, false)))
            assertEquals("voting", afterUnvote.proposal.status)
            assertEquals("cancelled", afterUnvote.proposal.booking?.status)
            assertEquals("cancelled", service.list(chatId, creator)!!.items.single().booking?.status)
            assertEquals(bookingId, afterUnvote.bookingChange?.bookingId)
            assertEquals("meeting_booking_cancelled", afterUnvote.emissions.single().draft.kind.databaseValue)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(1, count(connection, "notification_events", "recipient_user_id", nearby))
                assertEquals(1, count(connection, "meeting_proposals", "chat_id", chatId))
                assertEquals(1, count(connection, "votes", "proposal_id", created.proposal.id))
                connection.prepareStatement("SELECT status,cancel_reason FROM bookings WHERE id=?").use { statement ->
                    statement.setObject(1, bookingId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("CANCELLED", rows.getString(1))
                        assertEquals("INSUFFICIENT_VOTES", rows.getString(2))
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    private fun addUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, id); statement.executeUpdate() }
    }

    private fun count(connection: Connection, table: String, column: String, id: UUID): Int =
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $column=?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
}
