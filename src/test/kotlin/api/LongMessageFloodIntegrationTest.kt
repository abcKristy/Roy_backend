package com.example.api

import com.example.auth.*
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.chats.SendMessageCommand
import com.example.chats.SendMessageResult
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

class LongMessageFloodIntegrationTest {
    @Test
    fun `rolling five minute ledger enforces thirty successful sends across devices and chats`() = testApplication {
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
            suspend fun send(device:Int,chatId:UUID=chat,key:UUID=UUID.randomUUID(),text:String="Long window fixture")=clients[device].post("/api/v1/chats/$chatId/messages") {
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
            val empty=snapshot()
            assertEquals(HttpStatusCode.UnprocessableEntity,send(0,text="+79991234567").status)
            assertEquals(empty,snapshot())
            val keys=List(29){UUID.randomUUID()}
            var original:JsonObject?=null
            // Every ledger event below comes from a real successful HTTP send, never bulk SQL.
            for(index in 0 until 29) {
                clock.set(base.plusSeconds(index*2L))
                val response=send(index%2,if(index%2==0)chat else secondChat,key=keys[index],text="Accepted window item $index")
                assertEquals(HttpStatusCode.Created,response.status)
                val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(clock.get().toString(),body.getValue("createdAt").jsonPrimitive.content)
                if(index==0)original=body
            }
            clock.set(base.plusSeconds(58))
            val rollbackKey=UUID.randomUUID()
            val failureName="long_rate_"+rollbackKey.toString().replace("-","")
            val beforeFailure=snapshot()
            DriverManager.getConnection(url,user,password).use{c->c.createStatement().use{
                it.execute("CREATE FUNCTION $failureName() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.idempotency_key = ''$rollbackKey'' THEN RAISE EXCEPTION ''synthetic long rate rollback''; END IF; RETURN NEW; END;'")
                it.execute("CREATE TRIGGER $failureName BEFORE INSERT ON operation_idempotency FOR EACH ROW EXECUTE FUNCTION $failureName()")
            }}
            try {
                assertEquals(HttpStatusCode.InternalServerError,send(0,key=rollbackKey).status)
                assertEquals(beforeFailure,snapshot())
            } finally {
                DriverManager.getConnection(url,user,password).use{c->c.createStatement().use{
                    it.execute("DROP TRIGGER $failureName ON operation_idempotency");it.execute("DROP FUNCTION $failureName()")
                }}
            }
            val ready=List(2){CompletableDeferred<Unit>()};val start=CompletableDeferred<Unit>()
            val responses=coroutineScope {
                val requests=(0..1).map{device->async(Dispatchers.Default){ready[device].complete(Unit);start.await();send(device,if(device==0)chat else secondChat)}}
                ready.forEach{it.await()};start.complete(Unit);withTimeout(15000){requests.awaitAll()}
            }
            assertEquals(1,responses.count{it.status==HttpStatusCode.Created})
            val refused=responses.single{it.status==HttpStatusCode.TooManyRequests}
            assertEquals("242",refused.headers[HttpHeaders.RetryAfter]) // max(short=2,long=242).
            val error=Json.parseToJsonElement(refused.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals("MESSAGE_FLOOD",error.getValue("code").jsonPrimitive.content)
            assertEquals(242,error.getValue("retryAfterSeconds").jsonPrimitive.int)
            val full=snapshot()
            val replay=send(1,key=keys[0],text="Accepted window item 0")
            assertEquals(HttpStatusCode.Created,replay.status)
            assertEquals(original,Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
            assertEquals(full,snapshot())
            clock.set(base.plusSeconds(60));assertBlocked(240)
            // A fresh application-service instance still observes the committed ledger.
            val fresh=ChatSendService(runtime,MessageContentPolicy(),transactionClock={clock.get()})
            val freshResult=fresh.send(SendMessageCommand(actor,chat,UUID.randomUUID(),"Fresh instance request")){_,_->error("Flood must not build success DTO")}
            assertEquals(240,assertIs<SendMessageResult.Flood>(freshResult).retryAfterSeconds)
            assertEquals(full,snapshot())
            clock.set(base.plusSeconds(300).minusNanos(1000));assertBlocked(1)
            clock.set(base.plusSeconds(300))
            val atEdge=send(0)
            assertEquals(HttpStatusCode.Created,atEdge.status)
            assertEquals(clock.get().toString(),Json.parseToJsonElement(atEdge.bodyAsText()).jsonObject.getValue("createdAt").jsonPrimitive.content)
            fun activeCount()=DriverManager.getConnection(url,user,password).use{c->
                c.prepareStatement("SELECT count(*) FROM message_rate_events WHERE user_id=? AND accepted_at>?").use{
                    it.setObject(1,actor);it.setObject(2,clock.get().minusSeconds(300).atOffset(java.time.ZoneOffset.UTC));it.executeQuery().use{r->assertTrue(r.next());r.getInt(1)}
                }
            }
            assertEquals(30,activeCount()) // accepted_at==now-300 is excluded.
            clock.set(base.plusSeconds(300).plusNanos(1000));assertBlocked(2)
            clock.set(base.plusSeconds(301).plusMillis(200));assertBlocked(1)
            clock.set(base.plusSeconds(302));assertEquals(HttpStatusCode.Created,send(1,secondChat).status)
            assertEquals(30,activeCount()) // Rolling release of the second event, not a fixed bucket reset.
            clock.set(base.plusSeconds(304).plusNanos(1000));assertEquals(HttpStatusCode.Created,send(0).status)
            assertEquals(30,activeCount()) // Just after the next 300-second edge.
            DriverManager.getConnection(url,user,password).use{c->
                assertEquals(33,count(c,"message_rate_events","user_id",actor))
                assertEquals(33,count(c,"operation_idempotency","actor_user_id",actor))
                c.prepareStatement("SELECT count(*) FROM messages WHERE author_user_id=? AND chat_id IN (?,?)").use{
                    it.setObject(1,actor);it.setObject(2,chat);it.setObject(3,secondChat);it.executeQuery().use{r->assertTrue(r.next());assertEquals(33,r.getInt(1))}
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
