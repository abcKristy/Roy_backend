package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.moderation.ModerationService
import com.example.moderation.ReportCommand
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ReportContextIntegrationTest {
    @Test
    fun `report context and input validation reject without persisted side effects`() = testApplication {
        val url=com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user=System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password=System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val people=List(8){UUID.randomUUID()}
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
            val baseline=snapshot()
            suspend fun reject(json:String,status:HttpStatusCode,code:String,actor:UUID=reporter){repeat(2){
                val response=post(json,actor=actor);assertEquals(status,response.status)
                val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(code,error.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertFalse(error.containsKey("id"));assertFalse(error.toString().contains("Visible fixture text"));assertEquals(baseline,snapshot())
            }}
            for(target in listOf(outsider,noHistory,deleted,UUID.randomUUID()))reject(body(target),HttpStatusCode.Forbidden,"FORBIDDEN")
            reject(body(reporter),HttpStatusCode.UnprocessableEntity,"INVALID_FIELD")
            reject(body(),HttpStatusCode.Forbidden,"FORBIDDEN",outsider);reject(body(),HttpStatusCode.Forbidden,"FORBIDDEN",left)
            reject(body(context=foreign),HttpStatusCode.Forbidden,"FORBIDDEN");reject(body(context=UUID.randomUUID()),HttpStatusCode.Forbidden,"FORBIDDEN")
            DriverManager.getConnection(url,user,password).use{c -> c.prepareStatement("DELETE FROM chats WHERE id=?").use{it.setObject(1,foreign);assertEquals(1,it.executeUpdate())}}
            reject(body(context=foreign),HttpStatusCode.Forbidden,"FORBIDDEN")
            for(reason in listOf(""," \n\t","a\u0000b","🦊".repeat(1001)))reject(body(reason=reason),HttpStatusCode.UnprocessableEntity,"INVALID_FIELD")
            for(json in listOf("{}","[]",body().dropLast(1)+",\"extra\":1}",body().replace("\"Reason fixture\"","null")))reject(json,HttpStatusCode.UnprocessableEntity,"INVALID_FIELD")
            reject("{",HttpStatusCode.BadRequest,"INVALID_JSON")
            assertEquals(HttpStatusCode.Unauthorized,post(body(),auth=false).status)
            assertEquals(HttpStatusCode.BadRequest,post(body(),key=null).status);assertEquals(HttpStatusCode.BadRequest,post(body(),key="invalid").status)
            assertEquals(baseline,snapshot())
            assertFailsWith<IllegalArgumentException>{service.report(ReportCommand(reporter,UUID.randomUUID(),chat,member,"a\u0000b"))};assertEquals(baseline,snapshot())
            val key=UUID.randomUUID().toString();val reason="🦊".repeat(1000)
            val accepted=post(body(reason="  $reason  "),key);assertEquals(HttpStatusCode.Created,accepted.status)
            val result=Json.parseToJsonElement(accepted.bodyAsText()).jsonObject
            assertEquals(setOf("id","reportedAt"),result.keys);UUID.fromString(result.getValue("id").jsonPrimitive.content);Instant.parse(result.getValue("reportedAt").jsonPrimitive.content)
            val committed=snapshot();val replay=post(body(reason=reason),key);assertEquals(HttpStatusCode.Created,replay.status)
            assertEquals(result,Json.parseToJsonElement(replay.bodyAsText()).jsonObject);assertEquals(committed,snapshot())
            assertEquals(HttpStatusCode.Created,post(body(author)).status)
            assertEquals(HttpStatusCode.Created,post(body(control,"\u0001".repeat(1000))).status)
            DriverManager.getConnection(url,user,password).use{c ->
                c.prepareStatement("SELECT target_user_id,reason_text FROM reports WHERE reporter_user_id=?").use{it.setObject(1,reporter);it.executeQuery().use{r->val reasons=buildMap{while(r.next())put(r.getObject(1,UUID::class.java),r.getString(2))};assertEquals(mapOf(member to reason,author to "Reason fixture",control to "\u0001".repeat(1000)),reasons)}}
                c.prepareStatement("SELECT max(octet_length(response_snapshot::text)) FROM operation_idempotency WHERE actor_user_id=? AND operation_type='report'").use{it.setObject(1,reporter);it.executeQuery().use{r->assertTrue(r.next());assertTrue(r.getInt(1) in 1..16384)}}
            }
        }
    }
}
