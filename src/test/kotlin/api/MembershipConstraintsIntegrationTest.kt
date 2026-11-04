package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.PointWriteService
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MembershipConstraintsIntegrationTest {
    @Test
    fun `join returns canonical membership and repeat does not add member or message`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val point = UUID.randomUUID()
        val otherPoint = UUID.randomUUID()
        val otherChat = UUID.randomUUID()
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'test-hash','Join tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, phone)
                assertEquals(1, statement.executeUpdate())
            }
            for ((pointId, chatId) in listOf(point to chat, otherPoint to otherChat)) {
                connection.prepareStatement("""INSERT INTO points
                    (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                    VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Membership fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""").use {
                    it.setObject(1, pointId); it.setObject(2, actor); it.executeUpdate()
                }
                val topic = UUID.randomUUID()
                connection.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())").use {
                    it.setObject(1, topic); it.setObject(2, pointId); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1, chatId); it.setObject(2, topic); it.executeUpdate()
                }
            }
            connection.commit()
        }
        val secret = "join-route-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == session)
                        UserSelf(actor.toString(), "Join tester", null, phone, 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions, pointWriteService = PointWriteService(runtime))
            }
            val now = Instant.now()
            val bearer = "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            val path = "/api/v1/chats/$chat/membership"
            assertEquals(HttpStatusCode.Unauthorized, client.put(path).status)
            assertEquals(HttpStatusCode.NotFound, client.put(
                "/api/v1/chats/${UUID.randomUUID()}/membership") {
                header(HttpHeaders.Authorization, bearer)
            }.status)

            val before = DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor) }
            val first = client.put(path) { header(HttpHeaders.Authorization, bearer) }
            assertEquals(HttpStatusCode.OK, first.status)
            val body = Json.parseToJsonElement(first.bodyAsText()).jsonObject
            assertEquals(setOf("chatId", "userId", "joinedAt", "lastReadMessageSeq"), body.keys)
            assertEquals(chat.toString(), body.getValue("chatId").jsonPrimitive.content)
            assertEquals(actor.toString(), body.getValue("userId").jsonPrimitive.content)
            val afterFirst = DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor) }
            assertEquals(before.participants + 1, afterFirst.participants)
            assertEquals(before.lastMessageSeq + 1, afterFirst.lastMessageSeq)
            assertEquals(1, afterFirst.memberRows)
            assertEquals(1, afterFirst.joinMessages)
            assertEquals(afterFirst.lastMessageSeq.toString(), body.getValue("lastReadMessageSeq").jsonPrimitive.content)

            val repeat = client.put(path) { header(HttpHeaders.Authorization, bearer) }
            assertEquals(HttpStatusCode.OK, repeat.status)
            assertEquals(body, Json.parseToJsonElement(repeat.bodyAsText()).jsonObject)
            assertEquals(afterFirst, DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor) })

            // Rejoining an inactive row restores it once; the next PUT is again a pure replay.
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    "UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?"
                ).use { statement ->
                    statement.setObject(1, chat)
                    statement.setObject(2, actor)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val inactive = DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor) }
            assertEquals(before.participants, inactive.participants)
            val rejoin = client.put(path) { header(HttpHeaders.Authorization, bearer) }
            assertEquals(HttpStatusCode.OK, rejoin.status)
            val rejoinBody = Json.parseToJsonElement(rejoin.bodyAsText()).jsonObject
            assertNotEquals(body.getValue("joinedAt"), rejoinBody.getValue("joinedAt"))
            val afterRejoin = DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor) }
            assertEquals(before.participants + 1, afterRejoin.participants)
            assertEquals(1, afterRejoin.memberRows)
            assertEquals(2, afterRejoin.joinMessages)
            assertEquals(afterFirst.lastMessageSeq + 1, afterRejoin.lastMessageSeq)
            assertEquals(rejoinBody, Json.parseToJsonElement(client.put(path) {
                header(HttpHeaders.Authorization, bearer)
            }.bodyAsText()).jsonObject)
            assertEquals(afterRejoin, DriverManager.getConnection(url, user, password).use { snapshot(it, chat, actor) })
            // Actual constraint violations, isolated by autocommit, leave original rows intact.
            DriverManager.getConnection(url, user, password).use { connection ->
                val duplicateChat = kotlin.test.assertFailsWith<java.sql.SQLException> {
                    connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) SELECT ?,point_topic_id,clock_timestamp() FROM chats WHERE id=?").use {
                        it.setObject(1, UUID.randomUUID()); it.setObject(2, chat); it.executeUpdate()
                    }
                }
                assertEquals("23505", duplicateChat.sqlState)
                val duplicateMember = kotlin.test.assertFailsWith<java.sql.SQLException> {
                    connection.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                        it.setObject(1, chat); it.setObject(2, actor); it.executeUpdate()
                    }
                }
                assertEquals("23505", duplicateMember.sqlState)
                connection.prepareStatement("DELETE FROM points WHERE id=?").use {
                    it.setObject(1, point); assertEquals(1, it.executeUpdate())
                }
                for (table in listOf("chats", "chat_memberships", "messages")) {
                    val column = if (table == "chats") "id" else "chat_id"
                    connection.prepareStatement("SELECT count(*) FROM $table WHERE $column=?").use {
                        it.setObject(1, chat)
                        it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                    }
                }
                connection.prepareStatement("SELECT count(*) FROM chats WHERE id=?").use {
                    it.setObject(1, otherChat)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
                }
            }
            repeat(2) {
                val gone = client.put(path) { header(HttpHeaders.Authorization, bearer) }
                assertEquals(HttpStatusCode.NotFound, gone.status)
                assertEquals("RESOURCE_GONE", Json.parseToJsonElement(gone.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement("SELECT count(*) FROM chat_memberships WHERE chat_id=?").use {
                    it.setObject(1, chat)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                }
            }
        }
    }

    private data class Snapshot(val participants: Int, val lastMessageSeq: Long, val memberRows: Int, val joinMessages: Int)

    private fun snapshot(connection: Connection, chat: UUID, actor: UUID): Snapshot {
        val pair = connection.prepareStatement("SELECT participant_count,last_message_seq FROM chats WHERE id=?").use { statement ->
            statement.setObject(1, chat)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) to rows.getLong(2) }
        }
        val memberRows = connection.prepareStatement(
            "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL"
        ).use { statement ->
            statement.setObject(1, chat)
            statement.setObject(2, actor)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
        val joinMessages = connection.prepareStatement(
            """SELECT count(*) FROM messages WHERE chat_id=? AND system_type='member_joined'
               AND system_payload->>'actorUserId'=?"""
        ).use { statement ->
            statement.setObject(1, chat)
            statement.setString(2, actor.toString())
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
        return Snapshot(pair.first, pair.second, memberRows, joinMessages)
    }
}
