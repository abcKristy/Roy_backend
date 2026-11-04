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

class PhoneFilterIntegrationTest {
    @Test
    fun `approved phone forms are rejected without persistence or logging`() = testApplication {
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
        val sessionIds = listOf(actor, recipient, outsider).associateWith { UUID.randomUUID() }
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
            for ((id, session) in sessionIds) connection.prepareStatement("INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)").use {
                it.setObject(1,session); it.setObject(2,id); it.setString(3,session.toString().replace("-","").repeat(2)); it.executeUpdate()
            }
            connection.commit()
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, com.example.persistence.JdbcSessionRepository(runtime))
            application {
                configureTransport()
                routing { chatWriteRoutes(ChatSendService(runtime, MessageContentPolicy()), sessions) }
            }
            val now = Instant.now()
            fun token(id: UUID) = "Bearer " + JwtTokenIssuer(secret).issue(id, sessionIds.getValue(id), 0,
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
            val blocked = listOf(
                "Пиши +7 (999) 123-45-67", "8 (999) 123 45 67", "79991234567", "89991234567", "9991234567",
                "my phone +1 202 555 0143", "+1234567890", "+123456789012345", "+7\u00a0999\u00a0123-45-67",
                "звони 123 45 67", "тел: 12345678", "ТЕЛЕФОН-123456789", "CALL ME 1234567", "phone 123-45-67"
            )
            val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
            appender.start(); rootLogger.addAppender(appender)
            val rejectedKeys = mutableListOf<String>()
            try {
                for (text in blocked) {
                    val key = UUID.randomUUID().toString(); rejectedKeys += key
                    repeat(2) {
                        val response = send(body(text), key)
                        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
                        val encoded = response.bodyAsText()
                        val error = Json.parseToJsonElement(encoded).jsonObject.getValue("error").jsonObject
                        assertEquals("MESSAGE_REJECTED_PATTERN", error.getValue("code").jsonPrimitive.content)
                        assertEquals(buildJsonObject { put("category", "phone_or_url_or_profanity") }, error.getValue("details"))
                        assertFalse(encoded.contains(text)); assertFalse(error.containsKey("pattern")); assertFalse(error.containsKey("match"))
                        assertEquals(before, snapshot())
                    }
                }
                val captured = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.let(ch.qos.logback.classic.spi.ThrowableProxyUtil::asString) ?: "") }
                for (text in blocked) assertFalse(captured.contains(text), "Rejected synthetic text leaked to captured logs")
            } finally { rootLogger.detachAppender(appender); appender.stop() }
            val allowed = listOf(
                "Встречаемся в 12:30 у дома 12", "01.11.2026", "2026-11-01", "дом 12, квартира 45", "1000 рублей",
                "55.7558, 37.6173", "номер заказа 123 45 67", "12345678", "звони 123 45 6", "тел:   1234567",
                "+1 202  555    0143", "девять девять девять", "123. 456. 789", "a79991234567b"
            )
            for ((index,text) in allowed.withIndex()) {
                // Fixture ages successful rate events to isolate content rules from the separate flood gate.
                runtime.withJdbcTransaction { c -> c.prepareStatement("UPDATE message_rate_events SET accepted_at=clock_timestamp()-interval '10 seconds' WHERE user_id=?").use { it.setObject(1,actor); it.executeUpdate() } }
                val key = if(index==0) rejectedKeys.first() else UUID.randomUUID().toString()
                val accepted = send(body(text),key)
                assertEquals(HttpStatusCode.Created,accepted.status)
                val message = Json.parseToJsonElement(accepted.bodyAsText()).jsonObject
                assertEquals(text,message.getValue("text").jsonPrimitive.content)
                assertEquals(index+1L,message.getValue("messageSeq").jsonPrimitive.long)
                val committed = snapshot()
                assertEquals(List(6) { index+1L },committed)
                val replay = send(body(text),key)
                assertEquals(HttpStatusCode.Created,replay.status)
                assertEquals(message,Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
                assertEquals(committed,snapshot())
            }
            DriverManager.getConnection(url,user,password).use { c ->
                c.prepareStatement("SELECT body FROM messages WHERE chat_id=? ORDER BY message_seq").use {
                    it.setObject(1,chat); it.executeQuery().use { rows -> val saved=buildList { while(rows.next())add(rows.getString(1)) }; assertEquals(allowed,saved) }
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
