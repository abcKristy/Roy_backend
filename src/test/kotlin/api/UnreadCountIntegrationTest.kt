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
import kotlin.test.*

class UnreadCountIntegrationTest {
    @Test
    fun `unread counts actual text and system rows across sequence holes and own send`() = testApplication {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val sessions = listOf(actor,recipient).associateWith { UUID.randomUUID() }
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
            for(id in sessions.keys) c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at,last_read_seq) VALUES (?,?,clock_timestamp(),?)").use {
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
            suspend fun get(path:String,id:UUID):JsonObject {
                val response=client.get(path){header(HttpHeaders.Authorization,token(id))}
                assertEquals(HttpStatusCode.OK,response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            suspend fun unread(id:UUID):Long {
                val summary=get("/api/v1/me/chats",id).getValue("items").jsonArray.single().jsonObject
                val count=summary.getValue("unreadCount").jsonPrimitive
                assertFalse(count.isString)
                return count.long
            }
            suspend fun read(id:UUID,seq:Long):JsonObject {
                val response=client.put("/api/v1/chats/$chat/read-cursor") {
                    header(HttpHeaders.Authorization,token(id)); contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("lastReadMessageSeq",seq) }.toString())
                }
                assertEquals(HttpStatusCode.OK,response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            assertEquals(3L,unread(actor))
            assertEquals(4L,unread(recipient))
            repeat(2) {
                assertEquals(3L,get("/api/v1/chats/$chat",actor).getValue("unreadCount").jsonPrimitive.long)
                get("/api/v1/chats/$chat/messages",actor)
                get("/api/v1/chats/$chat/messages/search?q=Unread",actor)
                assertEquals(3L,unread(actor))
            }
            db.withJdbcTransaction { c ->
                c.prepareStatement("UPDATE chats SET last_message_seq=114 WHERE id=?").use {it.setObject(1,chat);it.executeUpdate()}
                c.prepareStatement("""INSERT INTO messages (id,chat_id,message_seq,type,author_user_id,body,created_at)
                    SELECT gen_random_uuid(),?,seq,'TEXT',?,'Unread bulk fixture',clock_timestamp() FROM generate_series(11,114) AS seq""").use {
                    it.setObject(1,chat);it.setObject(2,recipient);it.executeUpdate()
                }
            }
            assertEquals(107L,unread(actor))
            assertEquals(108L,unread(recipient))
            val key=UUID.randomUUID()
            suspend fun send()=client.post("/api/v1/chats/$chat/messages") {
                header(HttpHeaders.Authorization,token(actor));header("Idempotency-Key",key.toString())
                contentType(ContentType.Application.Json);setBody("{\"text\":\"Own message\"}")
            }
            val sent=send()
            assertEquals(HttpStatusCode.Created,sent.status)
            val body=Json.parseToJsonElement(sent.bodyAsText()).jsonObject
            assertEquals(115L,body.getValue("messageSeq").jsonPrimitive.long)
            assertEquals(0L,unread(actor))
            assertEquals(109L,unread(recipient))
            assertEquals(115L,read(actor,1).getValue("lastReadMessageSeq").jsonPrimitive.long)
            assertEquals(9L,read(recipient,9).getValue("lastReadMessageSeq").jsonPrimitive.long)
            assertEquals(106L,unread(recipient))
            assertEquals(9L,read(recipient,4).getValue("lastReadMessageSeq").jsonPrimitive.long)
            assertEquals(106L,unread(recipient))
            val replay=send()
            assertEquals(HttpStatusCode.Created,replay.status)
            assertEquals(body,Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
            assertEquals(0L,unread(actor));assertEquals(106L,unread(recipient))
            db.withReadOnlyJdbcTransaction { c ->
                c.prepareStatement("SELECT user_id,last_read_seq FROM chat_memberships WHERE chat_id=?").use {
                    it.setObject(1,chat)
                    it.executeQuery().use { rows ->
                        val cursors=buildMap { while(rows.next()) put(rows.getObject(1,UUID::class.java),rows.getLong(2)) }
                        assertEquals(mapOf(actor to 115L,recipient to 9L),cursors)
                    }
                }
                c.prepareStatement("SELECT count(*) FROM messages WHERE chat_id=?").use {
                    it.setObject(1,chat);it.executeQuery().use { rows -> assertTrue(rows.next());assertEquals(109,rows.getInt(1)) }
                }
            }
        }
    }
}
