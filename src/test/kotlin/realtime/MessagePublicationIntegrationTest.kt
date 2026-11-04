package com.example.realtime

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MessagePublicationIntegrationTest {
    @Test
    fun `committed message envelope is shared while replay and rollback publish no success`() = testApplication {
        val url=com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val dbUser=System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val dbPassword=System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val users=List(3){UUID.randomUUID()}
        val actor=users[0]
        val sessionIds=List(3){UUID.randomUUID()}
        val point=UUID.randomUUID()
        val chats=List(2){UUID.randomUUID()}
        DriverManager.getConnection(url, dbUser, dbPassword).use { c ->
            c.autoCommit = false
            for (id in users) {
                c.prepareStatement("""INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                    accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'test-hash','Socket fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""").use {
                    it.setObject(1,id); it.setString(2,"+7"+id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10,'0')); it.executeUpdate()
                }
            }
            for(id in users) c.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())").use {it.setObject(1,id);it.executeUpdate()}
            for ((index,id) in sessionIds.withIndex()) {
                c.prepareStatement("""INSERT INTO auth_sessions (id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                    VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)""").use {
                    it.setObject(1,id); it.setObject(2,users[index])
                    it.setString(3,id.toString().replace("-","").repeat(2)); it.executeUpdate()
                }
            }
            c.prepareStatement("""INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Socket fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""").use {
                it.setObject(1,point); it.setObject(2,actor); it.executeUpdate()
            }
            for ((index,chat) in chats.withIndex()) {
                val topic=UUID.randomUUID()
                c.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())").use {
                    it.setObject(1,topic); it.setObject(2,point); it.setString(3,if(index==0) "football" else "basketball"); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1,chat); it.setObject(2,topic); it.executeUpdate()
                }
                // A second member keeps the actor's old chat alive after leave.
                for(id in if(index==0) users.take(2) else listOf(users[2])) {
                    c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                        it.setObject(1,chat); it.setObject(2,id); it.executeUpdate()
                    }
                }
            }
            c.commit()
        }
        val secret="message-publication-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url,dbUser,dbPassword,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use {db ->
            val access=SessionAccessService(secret,JdbcSessionRepository(db))
            val hub=RealtimeHub(db,access)
            application {
                configureTransport();installRealtimeTransport()
                configureRouting(db,sessionAccess=access,chatSendService=ChatSendService(db,MessageContentPolicy()),realtimeHub=hub)
            }
            val now=Instant.now()
            val tokens=users.indices.map {"Bearer "+JwtTokenIssuer(secret).issue(users[it],sessionIds[it],0,now.minusSeconds(1),now.plusSeconds(3600))}
            suspend fun post(index:Int,key:UUID,text:String)=client.post("/api/v1/chats/${chats[0]}/messages") {
                header(HttpHeaders.Authorization,tokens[index]);header("Idempotency-Key",key.toString())
                contentType(ContentType.Application.Json);setBody(buildJsonObject{put("text",text)}.toString())
            }
            suspend fun sentinel(index:Int):UUID {
                val id=UUID.randomUUID()
                hub.publishCommitted(RealtimeEvent(id,RealtimeEventType.NOTIFICATION_CREATED,RealtimeScopeKind.USER,users[index],Instant.now(),buildJsonObject{put("marker",id.toString())}))
                return id
            }
            fun snapshot()=DriverManager.getConnection(url,dbUser,dbPassword).use {c ->
                listOf(
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.message_seq),'[]')::text FROM messages x WHERE chat_id=?" to chats[0],
                    "SELECT to_jsonb(x)::text FROM chats x WHERE id=?" to chats[0],
                    "SELECT to_jsonb(x)::text FROM points x WHERE id=?" to point,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM message_rate_events x WHERE user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.idempotency_key),'[]')::text FROM operation_idempotency x WHERE actor_user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.user_id),'[]')::text FROM chat_memberships x WHERE chat_id=?" to chats[0],
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM message_rate_events x WHERE user_id=?" to users[1],
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.idempotency_key),'[]')::text FROM operation_idempotency x WHERE actor_user_id=?" to users[1],
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM notification_events x WHERE chat_id=?" to chats[0]
                ).map{(sql,id)->c.prepareStatement(sql).use{it.setObject(1,id);it.executeQuery().use{r->assertTrue(r.next());r.getString(1)}}}
            }
            val ws=createClient{install(WebSockets)}
            ws.webSocket("/api/v1/ws",request={header(HttpHeaders.Authorization,tokens[0])}) {
                val author=this;subscribe(chats[0])
                ws.webSocket("/api/v1/ws",request={header(HttpHeaders.Authorization,tokens[1])}) {
                    val recipient=this;subscribe(chats[0])
                    ws.webSocket("/api/v1/ws",request={header(HttpHeaders.Authorization,tokens[2])}) {
                        val neighbor=this;subscribe(chats[1])
                        val key=UUID.randomUUID()
                        val response=post(0,key,"Committed publication fixture")
                        assertEquals(HttpStatusCode.Created,response.status)
                        val message=Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        val observed=listOf(author.next(),recipient.next())
                        val eventId=observed.first().getValue("eventId").jsonPrimitive.content
                        assertEquals(eventId,UUID.fromString(eventId).toString())
                        for(event in observed){
                            assertEquals(setOf("v","eventId","type","scope","occurredAt","payload"),event.keys)
                            assertEquals(1,event.getValue("v").jsonPrimitive.int)
                            assertEquals(eventId,event.getValue("eventId").jsonPrimitive.content)
                            assertEquals("chat.message.created",event.getValue("type").jsonPrimitive.content)
                            assertEquals(buildJsonObject{put("kind","chat");put("id",chats[0].toString())},event["scope"])
                            assertEquals(message,event["payload"])
                            assertEquals(message["createdAt"],event["occurredAt"])
                        }
                        // A separate JDBC connection observes the committed record after the received event.
                        DriverManager.getConnection(url,dbUser,dbPassword).use{c ->
                            c.prepareStatement("SELECT message_seq,body,author_user_id,created_at FROM messages WHERE id=? AND chat_id=?").use{
                                it.setObject(1,UUID.fromString(message.getValue("id").jsonPrimitive.content));it.setObject(2,chats[0])
                                it.executeQuery().use{r->assertTrue(r.next());assertEquals(message.getValue("messageSeq").jsonPrimitive.long,r.getLong(1));assertEquals(message.getValue("text").jsonPrimitive.content,r.getString(2));assertEquals(actor,r.getObject(3,UUID::class.java));assertEquals(Instant.parse(message.getValue("createdAt").jsonPrimitive.content),r.getTimestamp(4).toInstant());assertFalse(r.next())}
                            }
                        }
                        neighbor.noMessageThrough(sentinel(2))
                        val beforeReplay=snapshot()
                        val replay=post(0,key,"Committed publication fixture")
                        assertEquals(HttpStatusCode.Created,replay.status)
                        assertEquals(message,Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
                        assertEquals(beforeReplay,snapshot())
                        author.noMessageThrough(sentinel(0));recipient.noMessageThrough(sentinel(1));neighbor.noMessageThrough(sentinel(2))
                        val failureKey=UUID.randomUUID()
                        val trigger="publication_failure_"+failureKey.toString().replace("-","")
                        DriverManager.getConnection(url,dbUser,dbPassword).use{c->c.createStatement().use{
                            it.execute("CREATE FUNCTION $trigger() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.idempotency_key = ''$failureKey'' THEN RAISE EXCEPTION ''synthetic publication rollback''; END IF; RETURN NEW; END;'")
                            it.execute("CREATE TRIGGER $trigger BEFORE INSERT ON operation_idempotency FOR EACH ROW EXECUTE FUNCTION $trigger()")
                        }}
                        val beforeFailure=snapshot()
                        try {
                            assertEquals(HttpStatusCode.InternalServerError,post(1,failureKey,"Rollback publication fixture").status)
                            assertEquals(beforeFailure,snapshot())
                            author.noMessageThrough(sentinel(0));recipient.noMessageThrough(sentinel(1));neighbor.noMessageThrough(sentinel(2))
                        } finally {
                            DriverManager.getConnection(url,dbUser,dbPassword).use{c->c.createStatement().use{it.execute("DROP TRIGGER $trigger ON operation_idempotency");it.execute("DROP FUNCTION $trigger()")}}
                        }
                    }
                }
            }
        }
    }
    private suspend fun DefaultClientWebSocketSession.next():JsonObject=withTimeout(5000){
        var frame=incoming.receive();while(frame !is Frame.Text) frame=incoming.receive()
        Json.parseToJsonElement(frame.readText()).jsonObject
    }
    private suspend fun DefaultClientWebSocketSession.subscribe(chat:UUID){
        assertEquals("hello",next()["type"]?.jsonPrimitive?.content)
        send(Frame.Text(buildJsonObject{put("v",1);put("type","subscribe");put("requestId",UUID.randomUUID().toString());put("chatIds",JsonArray(listOf(JsonPrimitive(chat.toString()))))}.toString()))
        assertEquals(chat.toString(),next()["acceptedChatIds"]!!.jsonArray.single().jsonPrimitive.content)
    }
    private suspend fun DefaultClientWebSocketSession.noMessageThrough(sentinel:UUID)=withTimeout(5000){
        while(true){
            val event=next()
            assertNotEquals("chat.message.created",event["type"]?.jsonPrimitive?.content,"Unexpected or repeated message event")
            if(event["eventId"]?.jsonPrimitive?.content==sentinel.toString()) break
        }
        assertNull(withTimeoutOrNull(250){incoming.receive()},"Unexpected frame in bounded post-sentinel observation")
    }
}
