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

class PointChatLeaveRoutesIntegrationTest {
    @Test
    fun `muted member can leave and release vote and booking exactly once`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t5_leave$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val proposal = UUID.randomUUID()
        val booking = UUID.randomUUID()
        val chat = UUID.fromString("40000000-0000-4000-8000-000000000002")
        val point = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val otherVoter = UUID.fromString("10000000-0000-4000-8000-000000000001")
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
        }
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
