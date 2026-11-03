package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.chats.ChatReadService
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.test.*

class ReadCursorIntegrationTest {
    @Test
    fun `read cursor rejects invalid input and advances monotonically under concurrent requests`() = testApplication {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val sessions = listOf(actor,recipient,outsider).associateWith { UUID.randomUUID() }
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        DriverManager.getConnection(url,user,password).use { c ->
            c.autoCommit=false
            for ((id,session) in sessions) {
                c.prepareStatement("""INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                    accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'test-hash','Unread fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""").use {
                    it.setObject(1,id); it.setString(2,"+7"+id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10,'0')); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())").use { it.setObject(1,id); it.executeUpdate() }
                c.prepareStatement("""INSERT INTO auth_sessions (id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                    VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)""").use {
                    it.setObject(1,session); it.setObject(2,id); it.setString(3,session.toString().replace("-","").repeat(2)); it.executeUpdate()
                }
            }
            c.prepareStatement("""INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Unread fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""").use {
                it.setObject(1,point); it.setObject(2,actor); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())").use {
                it.setObject(1,topic); it.setObject(2,point); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO chats (id,point_topic_id,last_message_seq,created_at) VALUES (?,?,10,clock_timestamp())").use {
                it.setObject(1,chat); it.setObject(2,topic); it.executeUpdate()
            }
            for(id in listOf(actor,recipient)) c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at,last_read_seq) VALUES (?,?,clock_timestamp(),?)").use {
                it.setObject(1,chat); it.setObject(2,id); it.setInt(3,if(id==actor) 1 else 0); it.executeUpdate()
            }
            for(seq in listOf(1,4,9,10)) {
                val system = seq==1 || seq==9
                c.prepareStatement(if(system)
                    "INSERT INTO messages (id,chat_id,message_seq,type,system_type,system_payload,created_at) VALUES (?,?,?,'SYSTEM','member_joined','{}'::jsonb,clock_timestamp())"
                else "INSERT INTO messages (id,chat_id,message_seq,type,author_user_id,body,created_at) VALUES (?,?,?,'TEXT',?,'Unread text fixture',clock_timestamp())").use {
                    it.setObject(1,UUID.randomUUID()); it.setObject(2,chat); it.setInt(3,seq)
                    if(!system) it.setObject(4,recipient)
                    it.executeUpdate()
                }
            }
            c.commit()
        }
        val secret="unread-count-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use { db ->
            val access=SessionAccessService(secret,JdbcSessionRepository(db))
            application {
                configureTransport()
                routing { chatReadRoutes(ChatReadService(db,secret),access); chatWriteRoutes(ChatSendService(db,MessageContentPolicy()),access) }
            }
            val now=Instant.now()
            fun token(id:UUID)="Bearer "+JwtTokenIssuer(secret).issue(id,sessions.getValue(id),0,now.minusSeconds(1),now.plusSeconds(3600))
            suspend fun request(body:String,id:UUID=actor,authorization:Boolean=true)=client.put("/api/v1/chats/$chat/read-cursor") {
                if(authorization) header(HttpHeaders.Authorization,token(id))
                contentType(ContentType.Application.Json);setBody(body)
            }
            suspend fun advance(seq:Long):JsonObject {
                val response=request("{\"lastReadMessageSeq\":$seq}")
                assertEquals(HttpStatusCode.OK,response.status)
                val membership=Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(setOf("chatId","userId","joinedAt","lastReadMessageSeq"),membership.keys)
                assertEquals(chat.toString(),membership.getValue("chatId").jsonPrimitive.content)
                assertEquals(actor.toString(),membership.getValue("userId").jsonPrimitive.content)
                Instant.parse(membership.getValue("joinedAt").jsonPrimitive.content)
                return membership
            }
            suspend fun snapshot():JsonObject=db.withReadOnlyJdbcTransaction { c ->
                c.prepareStatement("""SELECT jsonb_build_object(
                    'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY m.user_id) FROM chat_memberships m WHERE chat_id=?),
                    'chat',(SELECT to_jsonb(c) FROM chats c WHERE id=?),
                    'messages',(SELECT jsonb_agg(to_jsonb(m) ORDER BY m.message_seq) FROM messages m WHERE chat_id=?),
                    'point',(SELECT to_jsonb(p) FROM points p WHERE id=?))::text""").use {
                    it.setObject(1,chat);it.setObject(2,chat);it.setObject(3,chat);it.setObject(4,point)
                    it.executeQuery().use { rows -> assertTrue(rows.next());Json.parseToJsonElement(rows.getString(1)).jsonObject }
                }
            }
            val original=snapshot()
            for(body in listOf("{\"lastReadMessageSeq\":-1}","{\"lastReadMessageSeq\":11}","{\"lastReadMessageSeq\":9223372036854775807}","{\"lastReadMessageSeq\":null}","{\"lastReadMessageSeq\":true}","{\"lastReadMessageSeq\":1.5}","{\"lastReadMessageSeq\":1,\"extra\":1}","{}")) {
                val response=request(body)
                assertEquals(HttpStatusCode.UnprocessableEntity,response.status)
                assertEquals("INVALID_FIELD",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(original,snapshot())
            }
            val malformed=request("{")
            assertEquals(HttpStatusCode.BadRequest,malformed.status)
            assertEquals("INVALID_JSON",Json.parseToJsonElement(malformed.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(original,snapshot())
            assertEquals(HttpStatusCode.Unauthorized,request("{\"lastReadMessageSeq\":4}",authorization=false).status)
            val denied=request("{\"lastReadMessageSeq\":4}",id=outsider)
            assertEquals(HttpStatusCode.Forbidden,denied.status)
            assertEquals("NOT_MEMBER",Json.parseToJsonElement(denied.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(original,snapshot())
            val zero=advance(0)
            assertEquals(1L,zero.getValue("lastReadMessageSeq").jsonPrimitive.long)
            assertEquals(original,snapshot())
            val four=advance(4)
            val fourState=snapshot()
            assertEquals(4L,four.getValue("lastReadMessageSeq").jsonPrimitive.long)
            assertEquals(four,advance(4));assertEquals(four,advance(1));assertEquals(fourState,snapshot())
            val ready=List(2){CompletableDeferred<Unit>()}
            val start=CompletableDeferred<Unit>()
            val results=withTimeout(15_000) {
                coroutineScope {
                    val jobs=listOf(6L,9L).mapIndexed { index,seq -> async(Dispatchers.Default) {
                        ready[index].complete(Unit);start.await();advance(seq)
                    } }
                    ready.forEach{it.await()};start.complete(Unit);jobs.awaitAll()
                }
            }
            assertTrue(results[0].getValue("lastReadMessageSeq").jsonPrimitive.long in 6L..9L)
            assertEquals(9L,results[1].getValue("lastReadMessageSeq").jsonPrimitive.long)
            val nine=advance(9)
            assertEquals(9L,nine.getValue("lastReadMessageSeq").jsonPrimitive.long)
            assertEquals(nine,advance(4))
            val ten=advance(10)
            assertEquals(10L,ten.getValue("lastReadMessageSeq").jsonPrimitive.long)
            assertEquals(ten,advance(0))
            // A persisted left membership has no read permission and cannot change its cursor.
            db.withJdbcTransaction { c -> c.prepareStatement("UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?").use {
                it.setObject(1,chat);it.setObject(2,recipient);it.executeUpdate()
            } }
            val leftState=snapshot()
            val left=request("{\"lastReadMessageSeq\":9}",id=recipient)
            assertEquals(HttpStatusCode.Forbidden,left.status)
            assertEquals("NOT_MEMBER",Json.parseToJsonElement(left.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(leftState,snapshot())
            db.withJdbcTransaction { c -> c.prepareStatement("UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE id=?").use {
                it.setObject(1,sessions.getValue(actor));it.executeUpdate()
            } }
            assertEquals(HttpStatusCode.Unauthorized,request("{\"lastReadMessageSeq\":10}").status)
            assertEquals(leftState,snapshot())
            val actorRow=leftState.getValue("members").jsonArray.single{it.jsonObject.getValue("user_id").jsonPrimitive.content==actor.toString()}.jsonObject
            assertEquals(10L,actorRow.getValue("last_read_seq").jsonPrimitive.long)
        }
    }
}
