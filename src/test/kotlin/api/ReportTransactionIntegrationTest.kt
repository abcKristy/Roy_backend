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
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ReportTransactionIntegrationTest {
    @Test
    fun `report target mute and outcome roll back on deferred commit failure`() = testApplication {
        val url=com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user=System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password=System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val people=List(23){UUID.randomUUID()}
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
            db.withJdbcTransaction{c ->
                for(prior in people.drop(8)) c.prepareStatement("INSERT INTO reports(id,reporter_user_id,target_user_id,context_chat_id,reason_text,created_at) VALUES (?,?,?,?,'Prior atomic fixture',clock_timestamp())").use{
                    it.setObject(1,UUID.randomUUID());it.setObject(2,prior);it.setObject(3,member);it.setObject(4,chat);assertEquals(1,it.executeUpdate())
                }
            }
            val key=UUID.randomUUID().toString()
            val trigger="report_commit_"+key.replace("-","")
            DriverManager.getConnection(url,user,password).use{c ->c.createStatement().use{
                it.execute("CREATE FUNCTION $trigger() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.idempotency_key = ''$key'' THEN RAISE EXCEPTION ''synthetic report commit failure''; END IF; RETURN NEW; END;'")
                it.execute("CREATE CONSTRAINT TRIGGER $trigger AFTER INSERT ON operation_idempotency DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION $trigger()")
            }}
            val baseline=snapshot()
            try{
                val failed=post(body(reason="Atomic report"),key)
                assertEquals(HttpStatusCode.InternalServerError,failed.status)
                assertEquals(baseline,snapshot())
                DriverManager.getConnection(url,user,password).use{c ->
                    c.prepareStatement("SELECT count(*) FROM reports WHERE target_user_id=?").use{it.setObject(1,member);it.executeQuery().use{r->assertTrue(r.next());assertEquals(15,r.getInt(1))}}
                    c.prepareStatement("SELECT message_muted_until,report_mute_triggered_at FROM users WHERE id=?").use{it.setObject(1,member);it.executeQuery().use{r->assertTrue(r.next());assertNull(r.getObject(1));assertNull(r.getObject(2))}}
                }
            }finally{
                DriverManager.getConnection(url,user,password).use{c ->c.createStatement().use{it.execute("DROP TRIGGER $trigger ON operation_idempotency");it.execute("DROP FUNCTION $trigger()")}}
            }
            fun time()=DriverManager.getConnection(url,user,password).use{c ->c.createStatement().use{it.executeQuery("SELECT clock_timestamp()").use{r->assertTrue(r.next());r.getObject(1,OffsetDateTime::class.java).toInstant()}}}
            val before=time()
            val accepted=post(body(reason="  Atomic report  "),key);assertEquals(HttpStatusCode.Created,accepted.status)
            val after=time();val result=Json.parseToJsonElement(accepted.bodyAsText()).jsonObject
            assertEquals(setOf("id","reportedAt"),result.keys)
            val id=UUID.fromString(result.getValue("id").jsonPrimitive.content)
            val at=Instant.parse(result.getValue("reportedAt").jsonPrimitive.content);assertTrue(at>=before && at<=after)
            DriverManager.getConnection(url,user,password).use{c ->
                c.prepareStatement("""SELECT r.reporter_user_id,r.target_user_id,r.context_chat_id,r.reason_text,r.created_at,
                    u.message_muted_until,u.report_mute_triggered_at,o.result_ref,o.response_snapshot,
                    (SELECT count(*) FROM reports WHERE target_user_id=?)
                    FROM reports r JOIN users u ON u.id=r.target_user_id
                    JOIN operation_idempotency o ON o.actor_user_id=r.reporter_user_id AND o.operation_type='report' AND o.idempotency_key=?
                    WHERE r.id=?""").use{
                    it.setObject(1,member);it.setString(2,key);it.setObject(3,id)
                    it.executeQuery().use{r ->
                        assertTrue(r.next());assertEquals(reporter,r.getObject(1,UUID::class.java));assertEquals(member,r.getObject(2,UUID::class.java));assertEquals(chat,r.getObject(3,UUID::class.java));assertEquals("Atomic report",r.getString(4))
                        assertEquals(at,r.getObject(5,OffsetDateTime::class.java).toInstant());assertEquals(at.plusSeconds(7*24*3600L),r.getObject(6,OffsetDateTime::class.java).toInstant());assertEquals(at,r.getObject(7,OffsetDateTime::class.java).toInstant());assertEquals(id,r.getObject(8,UUID::class.java))
                        val outcome=Json.parseToJsonElement(r.getString(9)).jsonObject;assertEquals(201,outcome.getValue("status").jsonPrimitive.int);assertEquals(result,outcome["body"]);assertEquals(16,r.getInt(10));assertFalse(r.next())
                    }
                }
            }
            val committed=snapshot()
            repeat(2){val replay=post(body(reason="Atomic report"),key);assertEquals(HttpStatusCode.Created,replay.status);assertEquals(result,Json.parseToJsonElement(replay.bodyAsText()).jsonObject);assertEquals(committed,snapshot())}
            for(json in listOf(body(reason="Changed reason"),body(target=control,reason="Atomic report"),body(reason="Atomic report",context=foreign))){
                val conflict=post(json,key);assertEquals(HttpStatusCode.Conflict,conflict.status)
                assertEquals("IDEMPOTENCY_KEY_REUSED",Json.parseToJsonElement(conflict.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content);assertEquals(committed,snapshot())
            }
        }
    }
}
