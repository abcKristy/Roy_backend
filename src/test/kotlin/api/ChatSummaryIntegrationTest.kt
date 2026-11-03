package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.chats.ChatReadService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ChatSummaryIntegrationTest {
    @Test
    fun `my chats projects only active membership and safe text system empty summaries`() = testApplication {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val actor = UUID.randomUUID()
        val neighbor = UUID.randomUUID()
        val emptyActor = UUID.randomUUID()
        val actors = listOf(actor, neighbor, emptyActor)
        val sessions = actors.associateWith { UUID.randomUUID() }
        val phones = actors.associateWith { "+7" + it.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0') }
        val point = UUID.randomUUID()
        val chats = List(5) { UUID.randomUUID() }
        val topics = List(5) { UUID.randomUUID() }
        val textMessage = UUID.randomUUID()
        val systemMessage = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { c ->
            c.autoCommit = false
            for (id in actors) {
                c.prepareStatement("""INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                    accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'private-password-hash','Private profile marker',37,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""").use {
                    it.setObject(1,id); it.setString(2,phones.getValue(id)); it.executeUpdate()
                }
                val session = sessions.getValue(id)
                c.prepareStatement("""INSERT INTO auth_sessions (id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                    VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)""").use {
                    it.setObject(1,session); it.setObject(2,id); it.setString(3,session.toString().replace("-", "").repeat(2)); it.executeUpdate()
                }
            }
            c.prepareStatement("""INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Summary address','MANUAL',?,clock_timestamp(),clock_timestamp())""").use {
                it.setObject(1,point); it.setObject(2,actor); it.executeUpdate()
            }
            for ((index,chat) in chats.withIndex()) {
                c.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,custom_label,created_at) VALUES (?,?,'other',?,clock_timestamp())").use {
                    it.setObject(1,topics[index]); it.setObject(2,point); it.setString(3,"Summary topic $index"); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO chats (id,point_topic_id,last_message_seq,created_at) VALUES (?,?,?,clock_timestamp())").use {
                    it.setObject(1,chat); it.setObject(2,topics[index]); it.setInt(3,if(index==0) 2 else if(index==1) 1 else 0); it.executeUpdate()
                }
                val members = when(index) { 0,4 -> listOf(actor,neighbor); 3 -> listOf(neighbor); else -> listOf(actor) }
                for (id in members) c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1,chat); it.setObject(2,id); it.executeUpdate()
                }
            }
            c.prepareStatement("UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?").use {
                it.setObject(1,chats[4]); it.setObject(2,actor); it.executeUpdate()
            }
            c.prepareStatement("""INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at)
                VALUES (?,?,1,?,'TEXT','Older sequence',clock_timestamp()+interval '1 day'),
                       (?,?,2,?,'TEXT','Latest text preview',clock_timestamp())""").use {
                it.setObject(1,UUID.randomUUID()); it.setObject(2,chats[0]); it.setObject(3,neighbor)
                it.setObject(4,textMessage); it.setObject(5,chats[0]); it.setObject(6,neighbor); it.executeUpdate()
            }
            c.prepareStatement("""INSERT INTO messages (id,chat_id,message_seq,type,system_type,system_payload,created_at)
                VALUES (?,?,1,'SYSTEM','member_left','{"privateProbe":"not a summary field"}'::jsonb,clock_timestamp())""").use {
                it.setObject(1,systemMessage); it.setObject(2,chats[1]); it.executeUpdate()
            }
            c.commit()
        }
        val secret = "chat-summary-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use { db ->
            application {
                configureTransport()
                routing { chatReadRoutes(ChatReadService(db,secret),SessionAccessService(secret,JdbcSessionRepository(db))) }
            }
            val now = Instant.now()
            fun bearer(id: UUID) = "Bearer " + JwtTokenIssuer(secret).issue(id,sessions.getValue(id),0,now.minusSeconds(1),now.plusSeconds(3600))
            suspend fun list(id: UUID): JsonObject {
                val response = client.get("/api/v1/me/chats") { header(HttpHeaders.Authorization,bearer(id)) }
                assertEquals(HttpStatusCode.OK,response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            val page = list(actor)
            assertEquals(setOf("items","nextCursor"),page.keys)
            assertEquals(JsonNull,page["nextCursor"])
            val items = page.getValue("items").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
            assertEquals(chats.take(3).map(UUID::toString).toSet(),items.keys)
            for (index in 0..2) {
                val item = items.getValue(chats[index].toString())
                assertEquals(setOf("id","pointId","topic","address","participantCount","lastMessage","unreadCount","currentProposal","updatedAt"),item.keys)
                assertEquals(point.toString(),item.getValue("pointId").jsonPrimitive.content)
                assertEquals("Summary address",item.getValue("address").jsonPrimitive.content)
                val count = if(index==0) 2 else 1
                assertEquals(count,item.getValue("participantCount").jsonPrimitive.int)
                val topic = item.getValue("topic").jsonObject
                assertEquals(setOf("id","presetKey","label","participantCount","chatId"),topic.keys)
                assertEquals(topics[index].toString(),topic.getValue("id").jsonPrimitive.content)
                assertEquals(chats[index].toString(),topic.getValue("chatId").jsonPrimitive.content)
                assertEquals("other",topic.getValue("presetKey").jsonPrimitive.content)
                assertEquals("Summary topic $index",topic.getValue("label").jsonPrimitive.content)
                assertEquals(count,topic.getValue("participantCount").jsonPrimitive.int)
                assertTrue(item.getValue("unreadCount").jsonPrimitive.long>=0)
                assertEquals(JsonNull,item["currentProposal"])
                Instant.parse(item.getValue("updatedAt").jsonPrimitive.content)
            }
            for ((index,id) in listOf(textMessage,systemMessage).withIndex()) {
                val preview = items.getValue(chats[index].toString()).getValue("lastMessage").jsonObject
                assertEquals(setOf("id","type","text","createdAt"),preview.keys)
                assertEquals(id.toString(),preview.getValue("id").jsonPrimitive.content)
                assertEquals(if(index==0) "text" else "system",preview.getValue("type").jsonPrimitive.content)
                assertEquals(if(index==0) "Latest text preview" else "Участник вышел",preview.getValue("text").jsonPrimitive.content)
                Instant.parse(preview.getValue("createdAt").jsonPrimitive.content)
            }
            assertEquals(JsonNull,items.getValue(chats[2].toString())["lastMessage"])
            assertEquals(page,list(actor))
            val encoded = page.toString()
            for (privateValue in phones.values + listOf("Private profile marker","private-password-hash","privateProbe",neighbor.toString(),"ageYears","phone","author","avatar")) {
                assertFalse(encoded.contains(privateValue),"Summary disclosed a private profile field")
            }
            val empty = list(emptyActor)
            assertTrue(empty.getValue("items").jsonArray.isEmpty())
            assertEquals(JsonNull,empty["nextCursor"])
            assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/me/chats").status)
            db.withReadOnlyJdbcTransaction { c ->
                c.prepareStatement("SELECT count(*) FROM chat_memberships WHERE user_id=? AND last_read_seq<>0").use {
                    it.setObject(1,actor); it.executeQuery().use { r -> assertTrue(r.next()); assertEquals(0,r.getInt(1)) }
                }
            }
        }
    }
}
