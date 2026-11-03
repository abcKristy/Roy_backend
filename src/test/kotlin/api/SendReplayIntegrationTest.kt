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
import kotlinx.coroutines.*
import com.example.chats.ChatReadService
import com.example.points.PointWriteService
import com.example.points.LeavePointChatResult

class SendReplayIntegrationTest {
    @Test
    fun `concurrent canonical replay preserves original snapshot across mutable state`() = testApplication {
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
            startApplication()
            val now = Instant.now()
            fun token(id: UUID) = "Bearer " + JwtTokenIssuer(secret).issue(id, UUID.randomUUID(), 0, now.minusSeconds(1), now.plusSeconds(3600))
            val actorToken = token(actor)
            suspend fun send(sender: String, key: UUID, text: String, id: UUID=chat) = client.post("/api/v1/chats/$id/messages") {
                header(HttpHeaders.Authorization,sender); header("Idempotency-Key",key.toString())
                contentType(ContentType.Application.Json); setBody(buildJsonObject { put("text",text) }.toString())
            }
            val key = UUID.randomUUID()
            val ready = listOf(CompletableDeferred<Unit>(),CompletableDeferred<Unit>())
            val start = CompletableDeferred<Unit>()
            val bodies = coroutineScope {
                val requests = listOf("  Original text  ","Original text").mapIndexed { index,text ->
                    async(Dispatchers.Default) {
                        ready[index].complete(Unit); start.await()
                        val response = send(actorToken,key,text)
                        assertEquals(HttpStatusCode.Created,response.status)
                        Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    }
                }
                ready.forEach { it.await() }; start.complete(Unit)
                withTimeout(15000) { requests.awaitAll() }
            }
            val original = bodies.first()
            assertEquals(original,bodies.last())
            assertEquals("Original text",original.getValue("text").jsonPrimitive.content)
            assertEquals(1L,original.getValue("messageSeq").jsonPrimitive.long)
            DriverManager.getConnection(url,user,password).use { c ->
                assertEquals(1,count(c,"messages","chat_id",chat)); assertEquals(1,count(c,"message_rate_events","user_id",actor))
                assertEquals(1,count(c,"operation_idempotency","actor_user_id",actor)); assertEquals(1,count(c,"notification_events","recipient_user_id",recipient))
            }
            for ((text,id) in listOf("Different text" to chat,"Original text" to UUID.randomUUID())) {
                val response = send(actorToken,key,text,id)
                assertEquals(HttpStatusCode.Conflict,response.status)
                assertEquals("IDEMPOTENCY_KEY_REUSED",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
            // Intentionally discard a committed response body; this is not TCP fault injection.
            assertEquals(HttpStatusCode.Created,send(token(recipient),key,"Independent sender").status)
            val recovered = send(token(recipient),key,"Independent sender")
            assertEquals(HttpStatusCode.Created,recovered.status)
            val other = Json.parseToJsonElement(recovered.bodyAsText()).jsonObject
            assertNotEquals(original.getValue("id"),other.getValue("id"))
            assertEquals(recipient.toString(),other.getValue("author").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(2L,other.getValue("messageSeq").jsonPrimitive.long)
            DriverManager.getConnection(url,user,password).use { c ->
                c.prepareStatement("SELECT response_snapshot->'body',result_ref FROM operation_idempotency WHERE actor_user_id=? AND operation_type='send_message' AND idempotency_key=?").use {
                    it.setObject(1,recipient); it.setString(2,key.toString())
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(other,Json.parseToJsonElement(rows.getString(1)).jsonObject); assertEquals(other.getValue("id").jsonPrimitive.content,rows.getObject(2).toString()); assertFalse(rows.next()) }
                }
                assertEquals(2,count(c,"messages","chat_id",chat)); assertEquals(1,count(c,"message_rate_events","user_id",recipient)); assertEquals(1,count(c,"operation_idempotency","actor_user_id",recipient))
            }
            runtime.withJdbcTransaction { c ->
                c.prepareStatement("UPDATE users SET display_name='Changed author',message_muted_until=clock_timestamp()+interval '1 day',updated_at=clock_timestamp() WHERE id=?").use { it.setObject(1,actor); it.executeUpdate() }
            }
            assertIs<com.example.chats.ReadCursorResult.Updated>(ChatReadService(runtime,secret).advanceReadCursor(actor,chat,2))
            assertIs<LeavePointChatResult.Left>(PointWriteService(runtime).leave(actor,chat))
            fun snapshot() = DriverManager.getConnection(url,user,password).use { c ->
                listOf(
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.message_seq),'[]')::text FROM messages x WHERE chat_id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM chats x WHERE id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM points x WHERE id=?" to point,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.user_id),'[]')::text FROM chat_memberships x WHERE chat_id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM users x WHERE id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM message_rate_events x WHERE user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.idempotency_key),'[]')::text FROM operation_idempotency x WHERE actor_user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM notification_events x WHERE chat_id=?" to chat
                ).map { (sql,id) -> c.prepareStatement(sql).use { it.setObject(1,id); it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) } } }
            }
            val stable = snapshot()
            repeat(2) {
                val replay = send(actorToken,key,"Original text")
                assertEquals(HttpStatusCode.Created,replay.status)
                assertEquals(original,Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
                assertEquals(stable,snapshot())
            }
            val expiredToken = "Bearer " + JwtTokenIssuer(secret).issue(actor, UUID.randomUUID(), 0,
                now.minusSeconds(3600), now.minusSeconds(1))
            val expired = send(expiredToken,key,"Original text")
            assertEquals(HttpStatusCode.Unauthorized,expired.status)
            assertEquals(stable,snapshot())
            val conflict = send(actorToken,key,"Changed text")
            assertEquals(HttpStatusCode.Conflict,conflict.status)
            assertEquals(stable,snapshot())
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
