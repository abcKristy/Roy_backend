package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.moderation.ModerationService
import java.time.OffsetDateTime
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MuteThresholdIntegrationTest {
    @Test
    fun `lifetime threshold crosses once under concurrent distinct reporters`() = testApplication {
        val url=com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user=System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password=System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val people=List(28){UUID.randomUUID()}
        val reporter=people[0];val member=people[1];val author=people[2];val outsider=people[3]
        val left=people[4];val deleted=people[5];val control=people[6];val noHistory=people[7]
        val sessions=people.associateWith{UUID.randomUUID()}
        val point=UUID.randomUUID();val chat=UUID.randomUUID();val foreign=UUID.randomUUID()
        DriverManager.getConnection(url,user,password).use{c ->
            c.autoCommit=false
            for(id in people){
                c.prepareStatement("""INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'test-hash','Report fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""").use{
                    it.setObject(1,id);it.setString(2,"+7"+id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10,'0'));it.executeUpdate()
                }
                val session=sessions.getValue(id)
                c.prepareStatement("INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)").use{
                    it.setObject(1,session);it.setObject(2,id);it.setString(3,session.toString().replace("-","").repeat(2));it.executeUpdate()
                }
            }
            c.prepareStatement("INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at) VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Report fixture','MANUAL',?,clock_timestamp(),clock_timestamp())").use{it.setObject(1,point);it.setObject(2,reporter);it.executeUpdate()}
            for((index,id) in listOf(chat,foreign).withIndex()){
                val topic=UUID.randomUUID()
                c.prepareStatement("INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())").use{it.setObject(1,topic);it.setObject(2,point);it.setString(3,if(index==0) "football" else "basketball");it.executeUpdate()}
                c.prepareStatement("INSERT INTO chats(id,point_topic_id,last_message_seq,created_at) VALUES (?,?,1,clock_timestamp())").use{it.setObject(1,id);it.setObject(2,topic);it.executeUpdate()}
            }
            for(id in people.filter{it!=outsider}) c.prepareStatement("INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use{it.setObject(1,chat);it.setObject(2,id);it.executeUpdate()}
            for(id in listOf(author,left,noHistory)) c.prepareStatement("UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?").use{it.setObject(1,chat);it.setObject(2,id);it.executeUpdate()}
            c.prepareStatement("UPDATE users SET phone_e164=NULL,password_hash=NULL,display_name=NULL,age_years=NULL,avatar_object_key=NULL,accepted_privacy_version=NULL,accepted_privacy_at=NULL,create_point_blocked_until=NULL,message_muted_until=NULL,report_mute_triggered_at=NULL,deleted_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?").use{it.setObject(1,deleted);it.executeUpdate()}
            for((context,target) in listOf(chat to author,foreign to outsider)) c.prepareStatement("INSERT INTO messages(id,chat_id,message_seq,type,author_user_id,body,created_at) VALUES (?,?,1,'TEXT',?,'Visible fixture text',clock_timestamp())").use{it.setObject(1,UUID.randomUUID());it.setObject(2,context);it.setObject(3,target);it.executeUpdate()}
            c.commit()
        }
        val secret="report-context-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use{db ->
            val service=ModerationService(db)
            application{configureTransport();configureRouting(db,sessionAccess=SessionAccessService(secret,JdbcSessionRepository(db)),moderationService=service)}
            val now=Instant.now()
            fun token(id:UUID)="Bearer "+JwtTokenIssuer(secret).issue(id,sessions.getValue(id),0,now.minusSeconds(1),now.plusSeconds(3600))
            fun body(target:UUID=member,reason:String="Reason fixture",context:UUID=chat)=buildJsonObject{put("contextChatId",context.toString());put("targetUserId",target.toString());put("reasonText",reason)}.toString()
            suspend fun post(json:String,key:String?=UUID.randomUUID().toString(),actor:UUID=reporter,auth:Boolean=true)=client.post("/api/v1/reports"){
                if(auth)header(HttpHeaders.Authorization,token(actor));if(key!=null)header("Idempotency-Key",key)
                contentType(ContentType.Application.Json);setBody(json)
            }
            fun snapshot()=DriverManager.getConnection(url,user,password).use{c ->
                listOf("SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]')::text FROM reports r WHERE reporter_user_id=ANY (?)",
                    "SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.actor_user_id,o.idempotency_key),'[]')::text FROM operation_idempotency o WHERE actor_user_id=ANY (?)",
                    "SELECT jsonb_agg(to_jsonb(u) ORDER BY u.id)::text FROM users u WHERE id=ANY (?)").map{sql ->
                    val ids=c.createArrayOf("uuid",people.toTypedArray())
                    try{c.prepareStatement(sql).use{it.setArray(1,ids);it.executeQuery().use{r->assertTrue(r.next());r.getString(1)}}}finally{ids.free()}
                }
            }
            val reporters=people.drop(8)
            data class TargetState(val until:Instant?,val triggered:Instant?,val updated:Instant)
            fun state()=DriverManager.getConnection(url,user,password).use{c ->
                c.prepareStatement("SELECT message_muted_until,report_mute_triggered_at,updated_at FROM users WHERE id=?").use{
                    it.setObject(1,member);it.executeQuery().use{r->assertTrue(r.next());TargetState(r.getObject(1,OffsetDateTime::class.java)?.toInstant(),r.getObject(2,OffsetDateTime::class.java)?.toInstant(),r.getObject(3,OffsetDateTime::class.java).toInstant())}
                }
            }
            fun counts()=DriverManager.getConnection(url,user,password).use{c ->
                c.prepareStatement("SELECT count(*),count(*) FILTER (WHERE context_chat_id IS NULL) FROM reports WHERE target_user_id=?").use{it.setObject(1,member);it.executeQuery().use{r->assertTrue(r.next());r.getInt(1) to r.getInt(2)}}
            }
            fun serverTime()=DriverManager.getConnection(url,user,password).use{c ->c.createStatement().use{it.executeQuery("SELECT clock_timestamp()").use{r->assertTrue(r.next());r.getObject(1,OffsetDateTime::class.java).toInstant()}}}
            val initial=state()
            for(actor in reporters.take(15)){
                assertEquals(HttpStatusCode.Created,post(body(),actor=actor).status)
                assertEquals(initial,state())
            }
            assertEquals(15 to 0,counts());assertNull(state().until);assertNull(state().triggered)
            // A deleted original context must not reset the lifetime threshold.
            db.withJdbcTransaction{c ->
                c.prepareStatement("DELETE FROM chats WHERE id=?").use{it.setObject(1,chat);assertEquals(1,it.executeUpdate())}
                for(actor in listOf(member)+reporters) c.prepareStatement("INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use{it.setObject(1,foreign);it.setObject(2,actor);it.executeUpdate()}
            }
            assertEquals(15 to 15,counts());assertEquals(initial,state())
            startApplication()
            val keys=List(2){UUID.randomUUID().toString()}
            val ready=List(2){CompletableDeferred<Unit>()};val start=CompletableDeferred<Unit>()
            val before=serverTime()
            val results=withTimeout(15000){coroutineScope{
                val jobs=keys.mapIndexed{index,key -> async(Dispatchers.Default){ready[index].complete(Unit);start.await();post(body(context=foreign),key,reporters[15+index])}}
                ready.forEach{it.await()};start.complete(Unit);jobs.awaitAll()
            }}
            val after=serverTime()
            assertTrue(results.all{it.status==HttpStatusCode.Created})
            val bodies=results.map{Json.parseToJsonElement(it.bodyAsText()).jsonObject}
            val times=bodies.map{Instant.parse(it.getValue("reportedAt").jsonPrimitive.content)}
            assertTrue(times.all{it>=before && it<=after})
            val muted=state();val triggered=assertNotNull(muted.triggered)
            assertEquals(times.minOrNull(),triggered)
            assertEquals(triggered.plusSeconds(7*24*3600L),muted.until)
            assertEquals(triggered,muted.updated)
            assertEquals(17 to 15,counts())
            // Replay both successful operations and a fresh duplicate without changing any state.
            val committed=snapshot()
            for(index in keys.indices){
                val replay=post(body(context=foreign),keys[index],reporters[15+index]);assertEquals(HttpStatusCode.Created,replay.status)
                assertEquals(bodies[index],Json.parseToJsonElement(replay.bodyAsText()).jsonObject);assertEquals(committed,snapshot())
            }
            val duplicate=post(body(context=foreign),actor=reporters[15]);assertEquals(HttpStatusCode.Conflict,duplicate.status)
            assertEquals("DUPLICATE_REPORT",Json.parseToJsonElement(duplicate.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(committed,snapshot());assertEquals(muted,state())
            assertEquals(HttpStatusCode.Created,post(body(context=foreign),actor=reporters[17]).status)
            assertEquals(18 to 15,counts());assertEquals(muted,state())
            // Fixture advances past the persisted deadline while retaining the one-time marker.
            val expired=serverTime().minusSeconds(1)
            db.withJdbcTransaction{c ->c.prepareStatement("UPDATE users SET message_muted_until=? WHERE id=?").use{it.setObject(1,expired.atOffset(java.time.ZoneOffset.UTC));it.setObject(2,member);assertEquals(1,it.executeUpdate())}}
            val expiredState=state();assertEquals(triggered,expiredState.triggered);assertEquals(expired,expiredState.until)
            assertEquals(HttpStatusCode.Created,post(body(context=foreign),actor=reporters[18]).status)
            assertEquals(19 to 15,counts());assertEquals(expiredState,state())
        }
    }
}