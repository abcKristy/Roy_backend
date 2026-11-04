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
import com.example.chats.ChatReadService
import com.example.points.PointWriteService

class MuteSendGuardIntegrationTest {
    @Test
    fun `persisted mute blocks both authenticated devices without send side effects`() = testApplication {
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
        HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use{runtime ->
            val sessions=SessionAccessService(secret,com.example.persistence.JdbcSessionRepository(runtime))
            application{configureTransport();configureRouting(runtime,sessionAccess=sessions,chatSendService=ChatSendService(runtime,MessageContentPolicy()),chatReadService=ChatReadService(runtime,secret),pointWriteService=PointWriteService(runtime))}
            val now=Instant.now()
            fun bearer(id:UUID,session:UUID)="Bearer "+JwtTokenIssuer(secret).issue(id,session,0,now.minusSeconds(1),now.plusSeconds(3600))
            val deviceTokens=devices.map{bearer(actor,it)}
            val recipientToken=bearer(recipient,recipientSession)
            suspend fun send(sender:String,key:UUID=UUID.randomUUID(),text:String="Guard fixture")=client.post("/api/v1/chats/$chat/messages"){
                header(HttpHeaders.Authorization,sender);header("Idempotency-Key",key.toString());contentType(ContentType.Application.Json);setBody(buildJsonObject{put("text",text)}.toString())
            }
            fun snapshot() = DriverManager.getConnection(url,user,password).use { c ->
                listOf(
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.message_seq),'[]')::text FROM messages x WHERE chat_id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM chats x WHERE id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM points x WHERE id=?" to point,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.user_id),'[]')::text FROM chat_memberships x WHERE chat_id=?" to chat,
                    "SELECT to_jsonb(x)::text FROM users x WHERE id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM message_rate_events x WHERE user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.idempotency_key),'[]')::text FROM operation_idempotency x WHERE actor_user_id=?" to actor,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM notification_events x WHERE chat_id=?" to chat,
                    "SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]')::text FROM push_delivery_jobs x JOIN notification_events e ON e.id=x.notification_event_id WHERE e.chat_id=?" to chat
                ).map { (sql,id) -> c.prepareStatement(sql).use { it.setObject(1,id); it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) } } }
            }
            val originalKey=UUID.randomUUID()
            val accepted=send(deviceTokens[0],originalKey);assertEquals(HttpStatusCode.Created,accepted.status)
            val original=Json.parseToJsonElement(accepted.bodyAsText()).jsonObject
            assertEquals(HttpStatusCode.Created,send(recipientToken,text="Recipient reply").status)
            val until=runtime.withJdbcTransaction{c ->
                c.prepareStatement("UPDATE users SET message_muted_until=clock_timestamp()+interval '7 days',report_mute_triggered_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=? RETURNING message_muted_until").use{
                    it.setObject(1,actor);it.executeQuery().use{r->assertTrue(r.next());r.getObject(1,java.time.OffsetDateTime::class.java).toInstant()}
                }
            }
            val muted=snapshot()
            for(token in deviceTokens){
                val denied=send(token);assertEquals(HttpStatusCode.Forbidden,denied.status)
                val error=Json.parseToJsonElement(denied.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("MESSAGE_MUTED",error.getValue("code").jsonPrimitive.content)
                assertEquals(setOf("mutedUntil"),error.getValue("details").jsonObject.keys)
                assertEquals(until,Instant.parse(error.getValue("details").jsonObject.getValue("mutedUntil").jsonPrimitive.content))
                assertEquals(muted,snapshot())
                val replay=send(token,originalKey);assertEquals(HttpStatusCode.Created,replay.status)
                assertEquals(original,Json.parseToJsonElement(replay.bodyAsText()).jsonObject);assertEquals(muted,snapshot())
            }
            for(path in listOf("/api/v1/me/chats","/api/v1/chats/$chat","/api/v1/chats/$chat/messages","/api/v1/chats/$chat/messages/search?q=fixture")){
                assertEquals(HttpStatusCode.OK,client.get(path){header(HttpHeaders.Authorization,deviceTokens[1])}.status)
                assertEquals(muted,snapshot())
            }
            val read=client.put("/api/v1/chats/$chat/read-cursor"){
                header(HttpHeaders.Authorization,deviceTokens[1]);contentType(ContentType.Application.Json);setBody("{\"lastReadMessageSeq\":2}")
            }
            assertEquals(HttpStatusCode.OK,read.status)
            DriverManager.getConnection(url,user,password).use{c->c.prepareStatement("SELECT last_read_seq FROM chat_memberships WHERE chat_id=? AND user_id=?").use{it.setObject(1,chat);it.setObject(2,actor);it.executeQuery().use{r->assertTrue(r.next());assertEquals(2,r.getInt(1))}}}
            // Direct persisted deadline fixture isolates the guard; T075 proves threshold creation.
            runtime.withJdbcTransaction{c ->
                c.prepareStatement("UPDATE users SET message_muted_until=clock_timestamp() WHERE id=?").use{it.setObject(1,actor);assertEquals(1,it.executeUpdate())}
                c.prepareStatement("UPDATE message_rate_events SET accepted_at=clock_timestamp()-interval '10 seconds' WHERE user_id=?").use{it.setObject(1,actor);assertEquals(1,it.executeUpdate())}
            }
            val expiredSend=send(deviceTokens[1],text="After deadline")
            assertEquals(HttpStatusCode.Created,expiredSend.status)
            assertEquals(3L,Json.parseToJsonElement(expiredSend.bodyAsText()).jsonObject.getValue("messageSeq").jsonPrimitive.long)
            DriverManager.getConnection(url,user,password).use{c ->
                assertEquals(3,count(c,"messages","chat_id",chat));assertEquals(2,count(c,"message_rate_events","user_id",actor));assertEquals(2,count(c,"operation_idempotency","actor_user_id",actor))
            }
            runtime.withJdbcTransaction{c ->c.prepareStatement("UPDATE users SET message_muted_until=clock_timestamp()+interval '1 day' WHERE id=?").use{it.setObject(1,actor);assertEquals(1,it.executeUpdate())}}
            assertEquals(HttpStatusCode.NoContent,client.delete("/api/v1/chats/$chat/membership"){header(HttpHeaders.Authorization,deviceTokens[1])}.status)
            DriverManager.getConnection(url,user,password).use{c ->
                c.prepareStatement("SELECT left_at IS NOT NULL FROM chat_memberships WHERE chat_id=? AND user_id=?").use{it.setObject(1,chat);it.setObject(2,actor);it.executeQuery().use{r->assertTrue(r.next());assertTrue(r.getBoolean(1))}}
                assertEquals(1,count(c,"chats","id",chat))
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
