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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import io.ktor.server.routing.routing
import com.example.chats.ChatReadService
import com.example.points.PointWriteService

class ShortMessageFloodIntegrationTest {
    @Test
    fun `short actor window respects exact clock boundaries across chats and devices`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val secret = "chat-route-test-secret-at-least-32-bytes"
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val devices=List(2){UUID.randomUUID()}
        val recipientSession=UUID.randomUUID()
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
            for((id,session) in devices.map{actor to it}+listOf(recipient to recipientSession)) connection.prepareStatement("INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)").use{
                it.setObject(1,session);it.setObject(2,id);it.setString(3,session.toString().replace("-","").repeat(2));it.executeUpdate()
            }
            connection.commit()
        }
        val secondChat=UUID.randomUUID()
        DriverManager.getConnection(url,user,password).use { c ->
            c.autoCommit=false
            val secondTopic=UUID.randomUUID()
            c.prepareStatement("INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'basketball',clock_timestamp())").use { it.setObject(1,secondTopic);it.setObject(2,point);it.executeUpdate() }
            c.prepareStatement("INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())").use { it.setObject(1,secondChat);it.setObject(2,secondTopic);it.executeUpdate() }
            for(id in listOf(actor,recipient)) c.prepareStatement("INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use { it.setObject(1,secondChat);it.setObject(2,id);it.executeUpdate() }
            c.commit()
        }
        val base=Instant.parse("2030-01-01T00:00:00Z")
        val clock=AtomicReference(base)
        HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION,databaseStatementTimeoutMs=15000,databaseLockTimeoutMs=10000)).start().use { runtime ->
            val sessions=SessionAccessService(secret,com.example.persistence.JdbcSessionRepository(runtime))
            val sendService=ChatSendService(runtime,MessageContentPolicy(),transactionClock={ clock.get() })
            application { configureTransport();routing { chatWriteRoutes(sendService,sessions) } }
            startApplication()
            val realNow=Instant.now()
            val tokens=devices.map { "Bearer "+JwtTokenIssuer(secret).issue(actor,it,0,realNow.minusSeconds(1),realNow.plusSeconds(3600)) }
            val clients=List(2){createClient{}}
            suspend fun send(device:Int,chatId:UUID=chat,key:UUID=UUID.randomUUID(),text:String="Short window fixture")=clients[device].post("/api/v1/chats/$chatId/messages") {
                header(HttpHeaders.Authorization,tokens[device]);header("Idempotency-Key",key.toString());contentType(ContentType.Application.Json);setBody(buildJsonObject {put("text",text)}.toString())
            }
            fun snapshot()=DriverManager.getConnection(url,user,password).use { c ->
                val specs=listOf(
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.message_seq),'[]')::text FROM messages x WHERE chat_id=?" to chat,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.message_seq),'[]')::text FROM messages x WHERE chat_id=?" to secondChat,
                    "SELECT to_jsonb(x)::text FROM chats x WHERE id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM chats x WHERE id=?" to secondChat,
                    "SELECT to_jsonb(x)::text FROM points x WHERE id=?" to point,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.chat_id),'[]')::text FROM chat_memberships x WHERE user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM message_rate_events x WHERE user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.idempotency_key),'[]')::text FROM operation_idempotency x WHERE actor_user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM notification_events x WHERE recipient_user_id=?" to recipient,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM push_delivery_jobs x JOIN notification_events e ON e.id=x.notification_event_id WHERE e.recipient_user_id=?" to recipient
                )
                specs.map { (sql,id)->c.prepareStatement(sql).use{it.setObject(1,id);it.executeQuery().use{r->assertTrue(r.next());r.getString(1)}} }
            }
            suspend fun assertBlocked(expected:Int) {
                val before=snapshot()
                val response=send(1,secondChat)
                assertEquals(HttpStatusCode.TooManyRequests,response.status)
                assertEquals(expected.toString(),response.headers[HttpHeaders.RetryAfter])
                val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("MESSAGE_FLOOD",error.getValue("code").jsonPrimitive.content)
                assertEquals(expected,error.getValue("retryAfterSeconds").jsonPrimitive.int)
                assertEquals(before,snapshot())
            }
            val firstKey=UUID.randomUUID()
            val first=send(0,key=firstKey);assertEquals(HttpStatusCode.Created,first.status)
            val original=Json.parseToJsonElement(first.bodyAsText()).jsonObject
            assertEquals(base.toString(),original.getValue("createdAt").jsonPrimitive.content)
            clock.set(base.plusMillis(100));assertBlocked(2)
            clock.set(base.plusMillis(1500));assertBlocked(1)
            clock.set(base.plusSeconds(2).minusNanos(1000));assertBlocked(1)
            clock.set(base.plusSeconds(2));assertEquals(HttpStatusCode.Created,send(1,secondChat).status)
            clock.set(base.plusSeconds(4).plusNanos(1000));assertEquals(HttpStatusCode.Created,send(0).status)
            // Prove the transaction clock is sampled after waiting for the actor lock.
            clock.set(base.plusSeconds(5))
            DriverManager.getConnection(url,user,password).use { blocker ->
                blocker.autoCommit=false
                blocker.prepareStatement("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE").use { it.setObject(1,actor);it.executeQuery().use{r->assertTrue(r.next())} }
                val blockerPid=blocker.createStatement().use{it.executeQuery("SELECT pg_backend_pid()").use{r->r.next();r.getInt(1)}}
                coroutineScope {
                    val pending=async(Dispatchers.Default){send(1,secondChat)}
                    try {
                        withTimeout(5000) {
                            while(true) {
                                val waiting=DriverManager.getConnection(url,user,password).use{c->c.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))").use{it.setInt(1,blockerPid);it.executeQuery().use{r->r.next();r.getBoolean(1)}}}
                                if(waiting)break
                                delay(10)
                            }
                        }
                        clock.set(base.plusSeconds(6).plusNanos(1000));blocker.commit()
                        assertEquals(HttpStatusCode.Created,pending.await().status)
                    } finally { blocker.rollback() }
                }
            }
            clock.set(base.plusSeconds(8).plusNanos(1000))
            val ready=List(2){CompletableDeferred<Unit>()};val start=CompletableDeferred<Unit>()
            val statuses=coroutineScope {
                val jobs=(0..1).map{device->async(Dispatchers.Default){ready[device].complete(Unit);start.await();send(device,if(device==0)chat else secondChat).status}}
                ready.forEach{it.await()};start.complete(Unit);withTimeout(15000){jobs.awaitAll()}
            }
            assertEquals(1,statuses.count{it==HttpStatusCode.Created});assertEquals(1,statuses.count{it==HttpStatusCode.TooManyRequests})
            val beforeReplay=snapshot()
            val replay=send(1,key=firstKey)
            assertEquals(HttpStatusCode.Created,replay.status);assertEquals(original,Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
            assertEquals(beforeReplay,snapshot());assertBlocked(2)
            clock.set(base.plusSeconds(10).plusNanos(1000))
            val beforeContentRefusal=snapshot()
            assertEquals(HttpStatusCode.UnprocessableEntity,send(0,text="+79991234567").status)
            assertEquals(beforeContentRefusal,snapshot())
            assertEquals(HttpStatusCode.Created,send(1,secondChat).status)
            DriverManager.getConnection(url,user,password).use { c ->
                assertEquals(6,count(c,"message_rate_events","user_id",actor))
                assertEquals(6,count(c,"operation_idempotency","actor_user_id",actor))
                c.prepareStatement("SELECT count(*),min(accepted_at),max(accepted_at) FROM message_rate_events WHERE user_id=?").use {
                    it.setObject(1,actor);it.executeQuery().use { r->assertTrue(r.next());assertEquals(6,r.getInt(1));assertEquals(base,r.getObject(2,java.time.OffsetDateTime::class.java).toInstant());assertEquals(clock.get(),r.getObject(3,java.time.OffsetDateTime::class.java).toInstant()) }
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
