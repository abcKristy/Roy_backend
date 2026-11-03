package com.example.chats

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

class SendTransactionIntegrationTest {
    @Test
    fun `concurrent sends allocate distinct sequence and outcome failure rolls back every write`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val secret = "chat-transaction-test-secret-at-least-32-bytes"
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
        DriverManager.getConnection(url, user, password).use { c ->
            c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                it.setObject(1, chat); it.setObject(2, outsider); it.executeUpdate()
            }
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment=AppEnvironment.PRODUCTION)).start().use { db ->
            val service = ChatSendService(db, MessageContentPolicy())
            fun response(c: Connection, m: MessageView) = buildJsonObject { put("id",m.id.toString()); put("text",m.body); put("messageSeq",m.messageSeq) }
            fun databaseTime() = DriverManager.getConnection(url,user,password).use { c ->
                c.createStatement().use { it.executeQuery("SELECT clock_timestamp()").use { rows -> assertTrue(rows.next()); rows.getObject(1,OffsetDateTime::class.java).toInstant() } }
            }
            val keys = listOf(UUID.randomUUID(), UUID.randomUUID())
            val ready = listOf(CompletableDeferred<Unit>(),CompletableDeferred<Unit>())
            val start = CompletableDeferred<Unit>()
            val before = databaseTime()
            val tasks = listOf(actor,recipient).mapIndexed { index, sender ->
                async(Dispatchers.Default) {
                    ready[index].complete(Unit); start.await()
                    service.send(SendMessageCommand(sender,chat,keys[index],"Concurrent text $index"),::response)
                }
            }
            ready.forEach { it.await() }; start.complete(Unit)
            val results = withTimeout(15000) { tasks.awaitAll() }.map { assertIs<SendMessageResult.Created>(it) }
            val after = databaseTime()
            assertEquals(setOf(1L,2L), results.map { it.message.messageSeq }.toSet())
            assertEquals(2, results.map { it.message.id }.toSet().size)
            assertTrue(results.all { it.message.createdAt >= before && it.message.createdAt <= after })
            db.withReadOnlyJdbcTransaction { c ->
                for ((i,sender) in listOf(actor,recipient).withIndex()) {
                    val message = results[i].message
                    c.prepareStatement("""SELECT m.message_seq,m.body,m.created_at,r.accepted_at,cm.last_read_seq,o.result_ref,o.response_snapshot
                        FROM messages m JOIN message_rate_events r ON r.user_id=m.author_user_id
                        JOIN chat_memberships cm ON cm.user_id=m.author_user_id AND cm.chat_id=m.chat_id
                        JOIN operation_idempotency o ON o.actor_user_id=m.author_user_id AND o.idempotency_key=?
                        WHERE m.chat_id=? AND m.author_user_id=?""").use {
                        it.setString(1,keys[i].toString()); it.setObject(2,chat); it.setObject(3,sender)
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next()); assertEquals(message.messageSeq,rows.getLong(1)); assertEquals(message.body,rows.getString(2))
                            assertEquals(message.createdAt,rows.getObject(3,OffsetDateTime::class.java).toInstant())
                            assertEquals(message.createdAt,rows.getObject(4,OffsetDateTime::class.java).toInstant())
                            assertEquals(message.messageSeq,rows.getLong(5)); assertEquals(message.id,rows.getObject(6,UUID::class.java))
                            assertEquals(results[i].responseSnapshot,Json.parseToJsonElement(rows.getString(7)).jsonObject); assertFalse(rows.next())
                        }
                    }
                }
            }
            fun snapshot() = DriverManager.getConnection(url,user,password).use { c ->
                val specs = listOf(
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.message_seq),'[]')::text FROM messages x WHERE chat_id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM chats x WHERE id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM points x WHERE id=?" to point,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.user_id),'[]')::text FROM chat_memberships x WHERE chat_id=?" to chat,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM message_rate_events x WHERE user_id=?" to outsider,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.idempotency_key),'[]')::text FROM operation_idempotency x WHERE actor_user_id=?" to outsider,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM notification_events x WHERE chat_id=?" to chat
                )
                specs.map { (sql,id) -> c.prepareStatement(sql).use {
                    it.setObject(1,id); it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
                } }
            }
            val rollbackKey = UUID.randomUUID()
            val command = SendMessageCommand(outsider,chat,rollbackKey,"Atomic retry text")
            val baseline = snapshot()
            val failureName = "send_outcome_" + rollbackKey.toString().replace("-", "")
            DriverManager.getConnection(url,user,password).use { c -> c.createStatement().use {
                it.execute("CREATE FUNCTION $failureName() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.idempotency_key = ''$rollbackKey'' THEN RAISE EXCEPTION ''synthetic outcome failure''; END IF; RETURN NEW; END;'")
                it.execute("CREATE TRIGGER $failureName BEFORE INSERT ON operation_idempotency FOR EACH ROW EXECUTE FUNCTION $failureName()")
            } }
            try {
                val failure = assertFailsWith<java.sql.SQLException> { service.send(command,::response) }
                assertEquals("P0001", failure.sqlState)
                assertEquals(baseline,snapshot())
            } finally {
                DriverManager.getConnection(url,user,password).use { c -> c.createStatement().use {
                    it.execute("DROP TRIGGER $failureName ON operation_idempotency"); it.execute("DROP FUNCTION $failureName()")
                } }
            }
            val accepted = assertIs<SendMessageResult.Created>(service.send(command,::response))
            assertEquals(3L,accepted.message.messageSeq)
            val committed = snapshot()
            val replay = assertIs<SendMessageResult.Replay>(service.send(command,::response))
            assertEquals(accepted.responseSnapshot,replay.responseSnapshot)
            assertEquals(committed,snapshot())
            db.withReadOnlyJdbcTransaction { c ->
                assertEquals(3,count(c,"messages","chat_id",chat))
                assertEquals(1,count(c,"message_rate_events","user_id",outsider))
                assertEquals(1,count(c,"operation_idempotency","actor_user_id",outsider))
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
