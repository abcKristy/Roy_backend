package com.example.api

import com.example.auth.*
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SendInputIntegrationTest {
    @Test
    fun `strict send input has no side effects before accepted canonical command`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val secret = "chat-route-test-secret-at-least-32-bytes"
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            listOf(actor, recipient, outsider).forEach { insertUser(connection, it) }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, point); statement.setObject(2, actor); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())")
                .use { statement -> statement.setObject(1, topic); statement.setObject(2, point); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())")
                .use { statement -> statement.setObject(1, chat); statement.setObject(2, topic); statement.executeUpdate() }
            listOf(actor, recipient).forEach { id ->
                connection.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())")
                    .use { statement -> statement.setObject(1, chat); statement.setObject(2, id); statement.executeUpdate() }
            }
            connection.commit()
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId in setOf(actor, recipient, outsider))
                        UserSelf(identity.userId.toString(), "Test", null, "+79990000000", 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                routing { chatWriteRoutes(ChatSendService(runtime, MessageContentPolicy()), sessions) }
            }
            val now = Instant.now()
            fun token(id: UUID) = "Bearer " + JwtTokenIssuer(secret).issue(id, UUID.randomUUID(), 0,
                now.minusSeconds(1), now.plusSeconds(3600))
            val actorToken = token(actor)
            suspend fun send(body: String, key: String? = UUID.randomUUID().toString(),
                             authorization: String? = actorToken, resource: String = chat.toString()) =
                client.post("/api/v1/chats/$resource/messages") {
                    authorization?.let { header(HttpHeaders.Authorization, it) }
                    key?.let { header("Idempotency-Key", it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            fun body(text: String) = buildJsonObject { put("text", text) }.toString()
            fun snapshot(): List<Long> = DriverManager.getConnection(url, user, password).use { c ->
                listOf(count(c, "messages", "chat_id", chat).toLong(),
                    count(c, "message_rate_events", "user_id", actor).toLong(),
                    count(c, "operation_idempotency", "actor_user_id", actor).toLong(),
                    count(c, "notification_events", "recipient_user_id", recipient).toLong()) +
                    c.prepareStatement("SELECT c.last_message_seq,m.last_read_seq FROM chats c JOIN chat_memberships m ON m.chat_id=c.id WHERE c.id=? AND m.user_id=?").use {
                        it.setObject(1, chat); it.setObject(2, actor)
                        it.executeQuery().use { rows -> assertTrue(rows.next()); listOf(rows.getLong(1), rows.getLong(2)) }
                    }
            }
            val before = snapshot()
            assertEquals(List(6) { 0L }, before)
            data class Invalid(val json: String, val status: HttpStatusCode, val code: String)
            for (invalid in listOf(
                Invalid("{", HttpStatusCode.BadRequest, "INVALID_JSON"),
                Invalid("{}", HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid("[]", HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid("{\"text\":null}", HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid("{\"text\":42}", HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid("{\"text\":\"Hello\",\"authorId\":\"$outsider\"}", HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid(body(""), HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid(body("a\u0000b"), HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid(body("  \n\t"), HttpStatusCode.UnprocessableEntity, "INVALID_FIELD"),
                Invalid(body("🦊".repeat(4097)), HttpStatusCode.UnprocessableEntity, "INVALID_FIELD")
            )) {
                val response = send(invalid.json)
                assertEquals(invalid.status, response.status)
                assertEquals(invalid.code, Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(before, snapshot())
            }
            for (key in listOf(null, "not-a-uuid", "")) {
                assertEquals(HttpStatusCode.BadRequest, send(body("Hello"), key=key).status)
                assertEquals(before, snapshot())
            }
            assertEquals(HttpStatusCode.BadRequest, send(body("Hello"), resource="invalid-chat-id").status)
            assertEquals(HttpStatusCode.Unauthorized, send(body("Hello"), authorization=null).status)
            assertEquals(HttpStatusCode.Unauthorized, send(body("Hello"), authorization="Bearer invalid").status)
            assertEquals(HttpStatusCode.Forbidden, send(body("Hello"), authorization=token(outsider)).status)
            assertEquals(HttpStatusCode.Forbidden, send(body("Hello"), resource=UUID.randomUUID().toString()).status)
            assertEquals(before, snapshot())
            val key = UUID.randomUUID().toString()
            val text = "🦊".repeat(4096)
            val accepted = send(body("  $text  "), key)
            assertEquals(HttpStatusCode.Created, accepted.status)
            val message = Json.parseToJsonElement(accepted.bodyAsText()).jsonObject
            assertEquals(text, message.getValue("text").jsonPrimitive.content)
            assertEquals(actor.toString(), message.getValue("author").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(chat.toString(), message.getValue("chatId").jsonPrimitive.content)
            assertEquals(1L, message.getValue("messageSeq").jsonPrimitive.long)
            assertEquals(listOf(1L,1L,1L,1L,1L,1L), snapshot())
            val repeated = send(body("  $text  "), key)
            assertEquals(HttpStatusCode.Created, repeated.status)
            assertEquals(message, Json.parseToJsonElement(repeated.bodyAsText()).jsonObject)
            assertEquals(listOf(1L,1L,1L,1L,1L,1L), snapshot())
            // JSONB emits this non-whitespace control as six ASCII bytes per codepoint.
            // A different active member avoids changing the successful-send rate ledger.
            val escapedKey = UUID.randomUUID().toString()
            val escapedText = "\u0001".repeat(4096)
            assertEquals(4096, escapedText.trim().codePointCount(0, escapedText.trim().length))
            val escaped = send(body(escapedText), escapedKey, authorization=token(recipient))
            assertEquals(HttpStatusCode.Created, escaped.status)
            assertEquals(escapedText, Json.parseToJsonElement(escaped.bodyAsText()).jsonObject.getValue("text").jsonPrimitive.content)
            DriverManager.getConnection(url, user, password).use { c ->
                c.prepareStatement("""SELECT octet_length(response_snapshot::text),response_snapshot->'body'->>'text'
                    FROM operation_idempotency WHERE actor_user_id=? AND operation_type='send_message' AND idempotency_key=?""").use {
                    it.setObject(1, recipient); it.setString(2, escapedKey)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getInt(1) in (4096 * 6 + 1)..65536)
                        assertEquals(escapedText, rows.getString(2)); assertFalse(rows.next())
                    }
                }
            }
            DriverManager.getConnection(url, user, password).use { c ->
                // Widen only send snapshots: other operations and JSON object shape stay bounded.
                for ((operation, snapshotJson) in listOf(
                    "other_operation" to buildJsonObject { put("padding", "x".repeat(17000)) }.toString(),
                    "send_message" to buildJsonObject { put("padding", "x".repeat(65536)) }.toString(),
                    "send_message" to "[]"
                )) {
                    val failure = assertFailsWith<java.sql.SQLException> {
                        c.prepareStatement("""INSERT INTO operation_idempotency
                            (actor_user_id,operation_type,idempotency_key,request_hash,outcome,response_snapshot,created_at)
                            VALUES (?,?,?,?,'SUCCESS',?::jsonb,clock_timestamp())""").use {
                            it.setObject(1, actor); it.setString(2, operation); it.setString(3, UUID.randomUUID().toString())
                            it.setString(4, "a".repeat(64)); it.setString(5, snapshotJson); it.executeUpdate()
                        }
                    }
                    assertEquals("23514", failure.sqlState)
                }
                c.prepareStatement("SELECT id,author_user_id,body FROM messages WHERE chat_id=? AND author_user_id=?").use {
                    it.setObject(1, chat); it.setObject(2, actor)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals(message.getValue("id").jsonPrimitive.content, rows.getObject(1).toString())
                        assertEquals(actor, rows.getObject(2, UUID::class.java)); assertEquals(text, rows.getString(3)); assertFalse(rows.next())
                    }
                }
            }
        }
    }

    private fun insertUser(connection: Connection, id: UUID) {
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
