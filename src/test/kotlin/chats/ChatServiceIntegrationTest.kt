package com.example.chats

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatServiceIntegrationTest {
    @Test
    fun `send search history cursors and recipient event remain consistent`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step5chatfinal$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val secret = "chat-service-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        runtime.use { db ->
            db.withJdbcTransaction { connection ->
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
            }
            val send = ChatSendService(db, MessageContentPolicy(ProfanityDetector { it.contains("badword") }))
            val read = ChatReadService(db, secret)
            val key = UUID.randomUUID()
            val first = assertIs<SendMessageResult.Created>(send.send(
                SendMessageCommand(actor, chat, key, "Hello world"), ::responseBody))
            assertEquals(1L, first.message.messageSeq)
            assertIs<SendMessageResult.Replay>(send.send(
                SendMessageCommand(actor, chat, key, "Hello world"), ::responseBody))
            assertIs<SendMessageResult.KeyReused>(send.send(
                SendMessageCommand(actor, chat, key, "Changed text"), ::responseBody))
            assertIs<SendMessageResult.Flood>(send.send(
                SendMessageCommand(actor, chat, UUID.randomUUID(), "Another message"), ::responseBody))
            db.withJdbcTransaction { connection ->
                connection.prepareStatement("UPDATE message_rate_events SET accepted_at=accepted_at-interval '3 seconds' WHERE user_id=?")
                    .use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
            }
            assertIs<SendMessageResult.RejectedPattern>(send.send(
                SendMessageCommand(actor, chat, UUID.randomUUID(), "badword"), ::responseBody))
            assertIs<SendMessageResult.RejectedPattern>(send.send(
                SendMessageCommand(actor, chat, UUID.randomUUID(), "https://example.org"), ::responseBody))
            assertIs<SendMessageResult.RejectedPattern>(send.send(
                SendMessageCommand(actor, chat, UUID.randomUUID(), "+79991234567"), ::responseBody))

            val second = assertIs<SendMessageResult.Created>(send.send(
                SendMessageCommand(actor, chat, UUID.randomUUID(), "100% fun world"), ::responseBody))
            assertEquals(2L, second.message.messageSeq)

            assertFailsWith<IllegalArgumentException> { read.search(actor, chat, "ab") }
            assertEquals(1, assertNotNull(read.search(actor, chat, "100%")) .items.size)
            val searchFirst = assertNotNull(read.search(actor, chat, "world", limit = 1))
            assertEquals(listOf(second.message.id), searchFirst.items.map { it.id })
            val searchSecond = assertNotNull(read.search(actor, chat, "world", searchFirst.nextCursor, limit = 1))
            assertEquals(listOf(first.message.id), searchSecond.items.map { it.id })
            assertNull(searchSecond.nextCursor)
            assertFailsWith<IllegalArgumentException> {
                read.search(actor, chat, "100%", searchFirst.nextCursor, limit = 1)
            }
            assertNull(read.search(outsider, chat, "world"))
            assertEquals(listOf(2L, 1L), assertNotNull(read.history(actor, chat)).items.map { it.messageSeq })
            assertEquals(listOf(2L), assertNotNull(read.history(actor, chat, afterSeq = 1)).items.map { it.messageSeq })
            assertEquals(listOf(1L), assertNotNull(read.history(actor, chat, beforeSeq = 2)).items.map { it.messageSeq })
            assertNull(read.history(outsider, chat))
            assertEquals(2L, assertNotNull(read.detail(recipient, chat)).summary.unreadCount)
            assertEquals(1, read.myChats(recipient).items.size)
            assertEquals(2L, assertIs<ReadCursorResult.Updated>(read.advanceReadCursor(recipient, chat, 2)).membership.lastReadSeq)
            assertEquals(2L, assertIs<ReadCursorResult.Updated>(read.advanceReadCursor(recipient, chat, 1)).membership.lastReadSeq)
            assertIs<ReadCursorResult.BeyondLastMessage>(read.advanceReadCursor(recipient, chat, 3))
            assertIs<ReadCursorResult.NotMember>(read.advanceReadCursor(outsider, chat, 3))
            assertEquals(0L, assertNotNull(read.detail(recipient, chat)).summary.unreadCount)
            db.withJdbcTransaction { connection ->
                assertEquals(2, countFor(connection, "messages", "chat_id", chat))
                assertEquals(2, countFor(connection, "message_rate_events", "user_id", actor))
                assertEquals(2, countFor(connection, "notification_events", "recipient_user_id", recipient))
                assertEquals(2L, lastSeq(connection, chat))
            }
            db.withJdbcTransaction { connection ->
                connection.prepareStatement("UPDATE message_rate_events SET accepted_at=clock_timestamp()-interval '10 seconds' WHERE user_id=?")
                    .use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
                connection.prepareStatement(
                    """INSERT INTO message_rate_events (id,user_id,accepted_at)
                       SELECT gen_random_uuid(),?,clock_timestamp()-interval '20 seconds'
                       FROM generate_series(1,28)"""
                ).use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
            }
            assertIs<SendMessageResult.Flood>(send.send(
                SendMessageCommand(actor, chat, UUID.randomUUID(), "After thirty"), ::responseBody))
            db.withJdbcTransaction { connection ->
                connection.prepareStatement("UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?")
                    .use { statement -> statement.setObject(1, chat); statement.setObject(2, actor); statement.executeUpdate() }
            }
            assertIs<SendMessageResult.Replay>(send.send(
                SendMessageCommand(actor, chat, key, "Hello world"), ::responseBody))
            assertIs<SendMessageResult.NotMember>(send.send(
                SendMessageCommand(actor, chat, UUID.randomUUID(), "After leave"), ::responseBody))
            Unit
        }
    }

    private fun responseBody(connection: Connection, message: MessageView) = buildJsonObject {
        put("id", message.id.toString())
        put("messageSeq", message.messageSeq)
        put("text", message.body)
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

    private fun countFor(connection: Connection, table: String, column: String, id: UUID): Int =
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $column=?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }

    private fun lastSeq(connection: Connection, chatId: UUID): Long = connection.prepareStatement(
        "SELECT last_message_seq FROM chats WHERE id=?"
    ).use { statement ->
        statement.setObject(1, chatId)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1) }
    }
}
