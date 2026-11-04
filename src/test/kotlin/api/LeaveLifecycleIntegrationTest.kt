package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.JoinPointChatResult
import com.example.points.PointWriteService
import com.example.points.LeavePointChatResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ThreadLocalRandom
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LeaveLifecycleIntegrationTest {
    @Test
    fun `muted member can leave and release vote and booking exactly once`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val proposal = UUID.randomUUID()
        val booking = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val point = UUID.randomUUID()
        val secondChat = UUID.randomUUID()
        val otherVoter = UUID.randomUUID()
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,message_muted_until,created_at,updated_at)
                   VALUES (?,?,'test-hash','Muted leaver',20,'test',clock_timestamp(),
                           clock_timestamp()+interval '1 day',clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, phone)
                assertEquals(1, statement.executeUpdate())
            }
            seedUser(connection, otherVoter)
            connection.prepareStatement("""INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Leave fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""").use {
                it.setObject(1, point); it.setObject(2, actor); it.executeUpdate()
            }
            for ((preset, id) in listOf("football" to chat, "basketball" to secondChat)) {
                val topic = UUID.randomUUID()
                connection.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())").use {
                    it.setObject(1, topic); it.setObject(2, point); it.setString(3, preset); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1, id); it.setObject(2, topic); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1, id); it.setObject(2, otherVoter); it.executeUpdate()
                }
            }
            connection.commit()
        }
        val secret = "leave-route-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val write = PointWriteService(runtime)
            assertIs<JoinPointChatResult.Joined>(write.join(actor, chat))
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.autoCommit = false
                connection.prepareStatement(
                    """INSERT INTO notification_settings (user_id,updated_at)
                       SELECT m.user_id,clock_timestamp() FROM chat_memberships AS m
                       WHERE m.chat_id=? AND m.left_at IS NULL
                       ON CONFLICT (user_id) DO NOTHING"""
                ).use { statement ->
                    statement.setObject(1, chat)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """INSERT INTO meeting_proposals (id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                       VALUES (?,?,?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 day 1 hour',
                               clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, proposal)
                    statement.setObject(2, chat)
                    statement.setObject(3, actor)
                    assertEquals(1, statement.executeUpdate())
                }
                for (voter in listOf(actor, otherVoter)) connection.prepareStatement(
                    "INSERT INTO votes (proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, proposal)
                    statement.setObject(2, voter)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    "INSERT INTO bookings (id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, booking)
                    statement.setObject(2, proposal)
                    statement.setObject(3, actor)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.commit()
            }
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == session)
                        UserSelf(actor.toString(), "Muted leaver", null, phone, 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions, pointWriteService = write)
            }
            val now = Instant.now()
            val bearer = "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            val path = "/api/v1/chats/$chat/membership"
            assertEquals(HttpStatusCode.Unauthorized, client.delete(path).status)
            val before = DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor, proposal, booking) }
            assertEquals(2, before.votes)
            assertEquals("ACTIVE", before.bookingStatus)
            assertTrue(before.muted)

            // Fail booking cancellation after vote removal: the complete leave must roll back.
            val failureFunction = "leave_failure_" + booking.toString().replace("-", "")
            DriverManager.getConnection(url, user, password).use { c ->
                c.createStatement().use {
                    it.execute("CREATE FUNCTION $failureFunction() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.id = ''$booking''::uuid THEN RAISE EXCEPTION ''synthetic leave failure''; END IF; RETURN NEW; END;'")
                    it.execute("CREATE TRIGGER $failureFunction BEFORE UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION $failureFunction()")
                }
            }
            try {
                assertEquals(HttpStatusCode.InternalServerError, client.delete(path) { header(HttpHeaders.Authorization, bearer) }.status)
                assertEquals(before, DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor, proposal, booking) })
            } finally {
                DriverManager.getConnection(url, user, password).use { c ->
                    c.createStatement().use {
                        it.execute("DROP TRIGGER $failureFunction ON bookings")
                        it.execute("DROP FUNCTION $failureFunction()")
                    }
                }
            }
            assertEquals(HttpStatusCode.NoContent, client.delete(path) {
                header(HttpHeaders.Authorization, bearer)
            }.status)
            val after = DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor, proposal, booking) }
            assertEquals(before.participants - 1, after.participants)
            assertEquals(before.lastMessageSeq + 2, after.lastMessageSeq)
            assertTrue(after.left)
            assertEquals(1, after.votes)
            assertEquals(0, after.actorVotes)
            assertEquals(before.revision + 1, after.revision)
            assertEquals("CANCELLED", after.bookingStatus)
            assertEquals("INSUFFICIENT_VOTES", after.cancelReason)
            assertEquals(1, after.memberLeftMessages)
            assertEquals(1, after.bookingCancelledMessages)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id = ?", point))
            }
            assertEquals(HttpStatusCode.NoContent, client.delete(path) {
                header(HttpHeaders.Authorization, bearer)
            }.status)
            assertEquals(after, DriverManager.getConnection(url, user, password).use {
                snapshot(it, chat, actor, proposal, booking)
            })
            val firstExit = assertIs<com.example.points.LeavePointChatResult.Left>(write.leave(otherVoter, chat))
            assertEquals(false, firstExit.pointDeleted)
            DriverManager.getConnection(url, user, password).use { c ->
                assertEquals(1, count(c, "SELECT count(*) FROM points WHERE id=?", point))
                assertEquals(2, count(c, "SELECT count(*) FROM point_topics WHERE point_id=?", point))
                assertEquals(1, count(c, "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND left_at IS NULL", secondChat))
            }
            val lastExit = assertIs<com.example.points.LeavePointChatResult.Left>(write.leave(otherVoter, secondChat))
            assertEquals(true, lastExit.pointDeleted)
            DriverManager.getConnection(url, user, password).use { c ->
                assertEquals(0, count(c, "SELECT count(*) FROM points WHERE id=?", point))
                assertEquals(0, count(c, "SELECT count(*) FROM point_topics WHERE point_id=?", point))
                for (id in listOf(chat, secondChat)) {
                    assertEquals(0, count(c, "SELECT count(*) FROM chats WHERE id=?", id))
                    assertEquals(0, count(c, "SELECT count(*) FROM messages WHERE chat_id=?", id))
                    assertEquals(0, count(c, "SELECT count(*) FROM chat_memberships WHERE chat_id=?", id))
                }
                assertEquals(0, count(c, "SELECT count(*) FROM bookings WHERE id=?", booking))
            }
        }
    }

    @Test
    fun `concurrent join and last leave serialize to a valid point state`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val first = UUID.randomUUID()
        val joining = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val ledger = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            listOf(first, joining).forEach { seedUser(connection, it) }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),'Race fixture','MANUAL',?,
                           clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, point)
                statement.setDouble(2, ThreadLocalRandom.current().nextDouble(-150.0, 150.0))
                statement.setDouble(3, ThreadLocalRandom.current().nextDouble(-60.0, 60.0))
                statement.setObject(4, first)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, topic)
                statement.setObject(2, point)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())").use { statement ->
                statement.setObject(1, chat)
                statement.setObject(2, topic)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, chat)
                statement.setObject(2, first)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO point_creation_events (id,creator_user_id,point_id,created_at) VALUES (?,?,?,clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, ledger)
                statement.setObject(2, first)
                statement.setObject(3, point)
                assertEquals(1, statement.executeUpdate())
            }
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "point-race-test-secret",
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val start = CompletableDeferred<Unit>()
            val readyJoin = CompletableDeferred<Unit>()
            val readyLeave = CompletableDeferred<Unit>()
            val joinRequest = async(Dispatchers.Default) {
                readyJoin.complete(Unit)
                start.await()
                service.join(joining, chat)
            }
            val lastLeaveRequest = async(Dispatchers.Default) {
                readyLeave.complete(Unit)
                start.await()
                service.leave(first, chat)
            }
            readyJoin.await()
            readyLeave.await()
            start.complete(Unit)
            val joinResult = joinRequest.await()
            val leaveResult = assertIs<LeavePointChatResult.Left>(lastLeaveRequest.await())
            assertEquals(point, leaveResult.pointId)
            DriverManager.getConnection(url, user, password).use { connection ->
                val pointRows = count(connection, "SELECT count(*) FROM points WHERE id=?", point)
                val topicRows = count(connection, "SELECT count(*) FROM point_topics WHERE id=?", topic)
                val chatRows = count(connection, "SELECT count(*) FROM chats WHERE id=?", chat)
                val activeMembers = count(connection,
                    "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND left_at IS NULL", chat)
                when (joinResult) {
                    is JoinPointChatResult.Joined -> {
                        assertEquals(false, leaveResult.pointDeleted)
                        assertEquals(1, pointRows)
                        assertEquals(1, topicRows)
                        assertEquals(1, chatRows)
                        assertEquals(1, activeMembers)
                        assertEquals(1, count(connection,
                            "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id IS NOT NULL AND left_at IS NOT NULL", chat))
                        assertEquals(1, count(connection,
                            "SELECT count(*) FROM point_creation_events WHERE id=? AND point_id IS NOT NULL", ledger))
                    }
                    JoinPointChatResult.ChatGone -> {
                        assertEquals(true, leaveResult.pointDeleted)
                        assertEquals(0, pointRows)
                        assertEquals(0, topicRows)
                        assertEquals(0, chatRows)
                        assertEquals(0, activeMembers)
                        assertEquals(0, count(connection, "SELECT count(*) FROM chat_memberships WHERE chat_id=?", chat))
                        assertEquals(0, count(connection, "SELECT count(*) FROM messages WHERE chat_id=?", chat))
                        assertEquals(1, count(connection,
                            "SELECT count(*) FROM point_creation_events WHERE id=? AND point_id IS NULL", ledger))
                    }
                    is JoinPointChatResult.AlreadyMember -> error("Joining actor was not a member before the race")
                }
                assertEquals(0, count(connection,
                    """SELECT count(*) FROM chats AS c LEFT JOIN point_topics AS t ON t.id=c.point_topic_id
                       WHERE c.id=? AND t.id IS NULL""", chat))
                assertTrue(connection.prepareStatement(
                    "SELECT point_id IS NULL FROM point_creation_events WHERE id=?"
                ).use { statement ->
                    statement.setObject(1, ledger)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) == leaveResult.pointDeleted }
                })
            }
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Race user',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); assertEquals(1, statement.executeUpdate()) }
    }

    private data class Snapshot(
        val participants: Int, val lastMessageSeq: Long, val left: Boolean, val muted: Boolean,
        val votes: Int, val actorVotes: Int, val revision: Int, val bookingStatus: String,
        val cancelReason: String?, val memberLeftMessages: Int, val bookingCancelledMessages: Int,
    )

    private fun snapshot(connection: Connection, chat: UUID, actor: UUID, proposal: UUID, booking: UUID): Snapshot {
        val chatState = connection.prepareStatement("SELECT participant_count,last_message_seq FROM chats WHERE id=?").use { statement ->
            statement.setObject(1, chat)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) to rows.getLong(2) }
        }
        val left = connection.prepareStatement(
            "SELECT left_at IS NOT NULL FROM chat_memberships WHERE chat_id=? AND user_id=?"
        ).use { statement ->
            statement.setObject(1, chat)
            statement.setObject(2, actor)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getBoolean(1) }
        }
        val muted = connection.prepareStatement("SELECT message_muted_until > clock_timestamp() FROM users WHERE id=?").use { statement ->
            statement.setObject(1, actor)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getBoolean(1) }
        }
        val proposalState = connection.prepareStatement(
            "SELECT revision,(SELECT count(*) FROM votes WHERE proposal_id=?) FROM meeting_proposals WHERE id=?"
        ).use { statement ->
            statement.setObject(1, proposal)
            statement.setObject(2, proposal)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) to rows.getInt(2) }
        }
        val bookingState = connection.prepareStatement("SELECT status,cancel_reason FROM bookings WHERE id=?").use { statement ->
            statement.setObject(1, booking)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) to rows.getString(2) }
        }
        return Snapshot(
            chatState.first, chatState.second, left, muted, proposalState.second,
            connection.prepareStatement("SELECT count(*) FROM votes WHERE proposal_id=? AND user_id=?").use { statement ->
                statement.setObject(1, proposal)
                statement.setObject(2, actor)
                statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
            }, proposalState.first, bookingState.first, bookingState.second,
            countSystemMessage(connection, chat, actor, "member_left"),
            countSystemMessage(connection, chat, actor, "meeting_booking_cancelled"),
        )
    }

    private fun countSystemMessage(connection: Connection, chat: UUID, actor: UUID, kind: String): Int =
        connection.prepareStatement(
            """SELECT count(*) FROM messages WHERE chat_id=? AND system_type=?
               AND system_payload->>'actorUserId'=?"""
        ).use { statement ->
            statement.setObject(1, chat)
            statement.setString(2, kind)
            statement.setString(3, actor.toString())
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }

    private fun count(connection: Connection, sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
