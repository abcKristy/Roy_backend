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
import io.ktor.client.request.parameter
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

class ChatListPaginationIntegrationTest {
    @Test
    fun `chat list uses scoped signed keyset cursors and stable created ordering`() = testApplication {
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
        val idPrefix = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
        val chats = List(7) { UUID(idPrefix,it.toLong()+1) }
        val topics = List(7) { UUID.randomUUID() }
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
                c.prepareStatement("INSERT INTO chats (id,point_topic_id,last_message_seq,created_at) VALUES (?,?,?,timestamptz '2026-01-01 00:00:00+00')").use {
                    it.setObject(1,chat); it.setObject(2,topics[index]); it.setInt(3,if(index==0) 2 else if(index==1) 1 else 0); it.executeUpdate()
                }
                val members = when(index) { 5 -> listOf(neighbor); else -> listOf(actor,neighbor) }
                for (id in members) c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1,chat); it.setObject(2,id); it.executeUpdate()
                }
            }
            c.prepareStatement("UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?").use {
                it.setObject(1,chats[6]); it.setObject(2,actor); it.executeUpdate()
            }
            c.prepareStatement("""INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at)
                VALUES (?,?,1,?,'TEXT','needle older',clock_timestamp()+interval '1 day'),
                       (?,?,2,?,'TEXT','needle latest',clock_timestamp())""").use {
                it.setObject(1,UUID.randomUUID()); it.setObject(2,chats[0]); it.setObject(3,neighbor)
                it.setObject(4,textMessage); it.setObject(5,chats[0]); it.setObject(6,neighbor); it.executeUpdate()
            }
            c.prepareStatement("""INSERT INTO messages (id,chat_id,message_seq,type,system_type,system_payload,created_at)
                VALUES (?,?,1,'SYSTEM','member_left','{"privateProbe":"not a summary field"}'::jsonb,clock_timestamp())""").use {
                it.setObject(1,systemMessage); it.setObject(2,chats[1]); it.executeUpdate()
            }
            c.prepareStatement("UPDATE chats SET created_at=timestamptz '2026-01-02 00:00:00+00' WHERE id=?").use {
                it.setObject(1,chats[0]);it.executeUpdate()
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
            suspend fun list(id:UUID=actor,limit:String="2",cursor:String?=null,status:HttpStatusCode=HttpStatusCode.OK):JsonObject {
                val response=client.get("/api/v1/me/chats") {
                    header(HttpHeaders.Authorization,bearer(id));parameter("limit",limit)
                    if(cursor!=null) parameter("cursor",cursor)
                }
                assertEquals(status,response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            fun ids(page:JsonObject)=page.getValue("items").jsonArray.map{it.jsonObject.getValue("id").jsonPrimitive.content}
            fun next(page:JsonObject)=page.getValue("nextCursor").jsonPrimitive.content
            val expected=(listOf(chats[0])+chats.take(5).drop(1).reversed()).map(UUID::toString)
            val first=list()
            assertEquals(expected.take(2),ids(first))
            val cursor=next(first)
            assertFalse(cursor.contains(actor.toString()))
            // New activity does not change the list's created_at/id keyset ordering.
            db.withJdbcTransaction { c ->
                c.prepareStatement("UPDATE chats SET last_message_seq=3 WHERE id=?").use {it.setObject(1,chats[1]);it.executeUpdate()}
                c.prepareStatement("INSERT INTO messages (id,chat_id,message_seq,type,author_user_id,body,created_at) VALUES (?,?,3,'TEXT',?,'needle newer',clock_timestamp())").use {
                    it.setObject(1,UUID.randomUUID());it.setObject(2,chats[1]);it.setObject(3,neighbor);it.executeUpdate()
                }
            }
            assertEquals(expected.take(2),ids(list()))
            val second=list(cursor=cursor)
            assertEquals(expected.drop(2).take(2),ids(second))
            assertEquals(ids(second),ids(list(cursor=cursor)))
            val end=list(cursor=next(second))
            assertEquals(expected.takeLast(1),ids(end))
            assertEquals(JsonNull,end["nextCursor"])
            assertEquals(expected,ids(first)+ids(second)+ids(end))
            assertEquals(5,(ids(first)+ids(second)+ids(end)).toSet().size)
            assertEquals(JsonNull,list(limit="5")["nextCursor"])
            val singles=mutableListOf<String>()
            var singleCursor:String?=null
            repeat(5) { index ->
                val page=list(limit="1",cursor=singleCursor)
                assertEquals(listOf(expected[index]),ids(page));singles+=ids(page)
                if(index==4) assertEquals(JsonNull,page["nextCursor"]) else singleCursor=next(page)
            }
            assertEquals(expected,singles)
            val empty=list(id=emptyActor)
            assertTrue(ids(empty).isEmpty());assertEquals(JsonNull,empty["nextCursor"])
            val searchResponse=client.get("/api/v1/chats/${chats[0]}/messages/search") {
                header(HttpHeaders.Authorization,bearer(actor));parameter("q","needle");parameter("limit",1)
            }
            assertEquals(HttpStatusCode.OK,searchResponse.status)
            val searchCursor=next(Json.parseToJsonElement(searchResponse.bodyAsText()).jsonObject)
            val invalid=listOf(
                list(cursor="A"+cursor,status=HttpStatusCode.BadRequest),
                list(cursor=cursor.substringBefore('.'),status=HttpStatusCode.BadRequest),
                list(cursor=cursor,id=neighbor,status=HttpStatusCode.BadRequest),
                list(cursor=cursor,limit="3",status=HttpStatusCode.BadRequest),
                list(cursor=searchCursor,status=HttpStatusCode.BadRequest)
            )
            for(error in invalid) {
                assertEquals("INVALID_CURSOR",error.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertFalse(error.containsKey("items"))
            }
            for(limit in listOf("0","101","-1","bad","9223372036854775808")) {
                val error=list(limit=limit,status=HttpStatusCode.BadRequest)
                assertEquals("INVALID_FIELD",error.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
            // Pagination deliberately promises no frozen membership snapshot.
            db.withReadOnlyJdbcTransaction { c ->
                c.prepareStatement("SELECT count(*) FROM chat_memberships WHERE user_id=? AND last_read_seq<>0").use {
                    it.setObject(1,actor);it.executeQuery().use {r->assertTrue(r.next());assertEquals(0,r.getInt(1))}
                }
            }
        }
    }
}
