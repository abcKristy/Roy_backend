package com.example.acceptance

import com.example.api.*
import com.example.auth.SessionAccessService
import com.example.chats.*
import com.example.config.*
import com.example.persistence.*
import com.example.points.PointWriteService
import com.example.moderation.ModerationService
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ChatRestAcceptanceIntegrationTest {
    @Test
    fun `joined actor completes persisted chat lifecycle with authoritative reads`() = testApplication {
        val url = com.example.testing.testDatabaseUrl()
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "rest-acceptance-secret-at-least-32-bytes"
        val trace = mutableListOf<JsonObject>()
        ChatAcceptanceFixture(url, user, password).use { f ->
            HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use { db ->
                application { configureTransport(); configureRouting(db,
                    sessionAccess=SessionAccessService(secret,JdbcSessionRepository(db)),
                    pointWriteService=PointWriteService(db),chatReadService=ChatReadService(db,secret),
                    chatSendService=ChatSendService(db,MessageContentPolicy()),moderationService=ModerationService(db)) }
                val chat=f.chats[0]; val actor=f.outsider
                fun state() = f.connection().use { c ->
                    val queries=listOf(
                        "SELECT count(*) FROM messages WHERE chat_id=?" to chat,
                        "SELECT last_message_seq FROM chats WHERE id=?" to chat,
                        "SELECT count(*) FROM message_rate_events WHERE user_id=?" to actor,
                        "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?" to actor,
                        "SELECT coalesce(max(last_read_seq),0) FROM chat_memberships WHERE chat_id=? AND user_id=?" to chat,
                        "SELECT count(*) FROM reports WHERE target_user_id=?" to f.peer)
                    queries.map { (sql,id) -> c.prepareStatement(sql).use { s -> s.setObject(1,id);if(sql.endsWith("AND user_id=?"))s.setObject(2,actor);s.executeQuery().use { r -> assertTrue(r.next());r.getLong(1) } } }
                }
                suspend fun request(method:HttpMethod,path:String,asActor:UUID=actor,body:JsonObject?=null,key:UUID?=null,expected:Int):JsonObject {
                    val requestId=UUID.randomUUID().toString()
                    val before=state()
                    val response=client.request(path) {
                        this.method=method;header(HttpHeaders.Authorization,f.bearer(asActor,secret));header("X-Request-Id",requestId)
                        if(key!=null)header("Idempotency-Key",key.toString())
                        if(body!=null){contentType(ContentType.Application.Json);setBody(body.toString())}
                    }
                    assertEquals(expected,response.status.value)
                    assertEquals(requestId,response.headers["X-Request-Id"])
                    val text=response.bodyAsText()
                    val actual=if(text.isEmpty())JsonNull else Json.parseToJsonElement(text)
                    trace += buildJsonObject {
                        put("requestId",requestId);put("method",method.value);put("path",path);put("actorId",asActor.toString())
                        put("idempotencyKey",key?.let{JsonPrimitive(it.toString())}?:JsonNull)
                        put("requestBody",body?:JsonNull);put("expectedStatus",expected);put("actualStatus",response.status.value)
                        put("responseBody",actual);put("dbBefore",JsonArray(before.map(::JsonPrimitive)));put("dbAfter",JsonArray(state().map(::JsonPrimitive)))
                    }
                    return actual as? JsonObject ?: buildJsonObject {}
                }
                val history="/api/v1/chats/$chat/messages"
                val initial=state()
                request(HttpMethod.Get,history,expected=403);assertEquals(initial,state())
                val joined=request(HttpMethod.Put,"/api/v1/chats/$chat/membership",expected=200)
                assertEquals(actor.toString(),joined.getValue("userId").jsonPrimitive.content)
                val afterJoin=state();assertEquals(3L,afterJoin[0]);assertEquals(3L,afterJoin[1])
                request(HttpMethod.Put,"/api/v1/chats/$chat/membership",expected=200);assertEquals(afterJoin,state())
                val priorHistory=request(HttpMethod.Get,history,expected=200)
                val priorItems = priorHistory.getValue("items").jsonArray
                assertEquals(3,priorItems.size)
                assertMembershipSystemMessage(priorItems[0].jsonObject,f.points[0],chat,actor)
                assertMembershipSystemMessage(priorItems[1].jsonObject,f.points[0],chat,f.peer)
                assertEquals(f.messages[1].toString(),priorItems[1].jsonObject.getValue("id").jsonPrimitive.content)
                val blank=buildJsonObject{put("text"," ")}
                request(HttpMethod.Post,history,body=blank,key=UUID.randomUUID(),expected=422);assertEquals(afterJoin,state())
                val key=UUID.randomUUID();val body=buildJsonObject{put("text","Synthetic acceptance chain")}
                val sent=request(HttpMethod.Post,history,body=body,key=key,expected=201)
                val seq=sent.getValue("messageSeq").jsonPrimitive.long;assertEquals(4L,seq)
                val accepted=state();assertEquals(4L,accepted[0]);assertEquals(1L,accepted[2]);assertEquals(1L,accepted[3])
                f.connection().use { c -> c.prepareStatement("SELECT body,message_seq FROM messages WHERE id=?").use { s ->
                    s.setObject(1,UUID.fromString(sent.getValue("id").jsonPrimitive.content));s.executeQuery().use{r->assertTrue(r.next());assertEquals(body.getValue("text").jsonPrimitive.content,r.getString(1));assertEquals(seq,r.getLong(2))}
                } }
                assertEquals(sent,request(HttpMethod.Post,history,body=body,key=key,expected=201));assertEquals(accepted,state())
                val delta=request(HttpMethod.Get,"$history?afterSeq=3",expected=200).getValue("items").jsonArray
                assertEquals(listOf(sent),delta.toList())
                val search=request(HttpMethod.Get,"$history/search?q=acceptance",expected=200).getValue("items").jsonArray
                assertEquals(listOf(sent),search.toList())
                val peerDetail=request(HttpMethod.Get,"/api/v1/chats/$chat",asActor=f.peer,expected=200)
                assertEquals(4L,peerDetail.getValue("unreadCount").jsonPrimitive.long)
                request(HttpMethod.Put,"/api/v1/chats/$chat/read-cursor",asActor=f.peer,body=buildJsonObject{put("lastReadMessageSeq",seq)},expected=200)
                assertEquals(0L,request(HttpMethod.Get,"/api/v1/chats/$chat",asActor=f.peer,expected=200).getValue("unreadCount").jsonPrimitive.long)
                f.connection().use { c -> c.prepareStatement("SELECT last_read_seq FROM chat_memberships WHERE chat_id=? AND user_id=?").use { s ->
                    s.setObject(1,chat);s.setObject(2,f.peer);s.executeQuery().use{r->assertTrue(r.next());assertEquals(seq,r.getLong(1))}
                } }
                val reportBody=buildJsonObject{put("contextChatId",chat.toString());put("targetUserId",f.peer.toString());put("reasonText","Synthetic acceptance reason")}
                val reportKey=UUID.randomUUID()
                val report=request(HttpMethod.Post,"/api/v1/reports",body=reportBody,key=reportKey,expected=201)
                assertEquals(report,request(HttpMethod.Post,"/api/v1/reports",body=reportBody,key=reportKey,expected=201))
                assertEquals(1L,state()[5])
                for(reporter in f.reporters.take(15)) request(HttpMethod.Post,"/api/v1/reports",asActor=reporter,body=reportBody,key=UUID.randomUUID(),expected=201)
                assertEquals(16L,state()[5])
                val mutedState=state()
                val muted=request(HttpMethod.Post,history,asActor=f.peer,body=body,key=UUID.randomUUID(),expected=403)
                assertEquals("MESSAGE_MUTED",muted.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(mutedState,state())
                request(HttpMethod.Get,"/api/v1/chats/$chat",asActor=f.peer,expected=200)
                request(HttpMethod.Delete,"/api/v1/chats/$chat/membership",expected=204)
                val left=state();assertEquals(5L,left[0]);assertEquals(5L,left[1])
                val leftItems=request(HttpMethod.Get,history,asActor=f.peer,expected=200).getValue("items").jsonArray
                assertMembershipSystemMessage(leftItems[0].jsonObject,f.points[0],chat,actor,"member_left")
                assertEquals(5L,leftItems[0].jsonObject.getValue("messageSeq").jsonPrimitive.long)
                request(HttpMethod.Delete,"/api/v1/chats/$chat/membership",expected=204);assertEquals(left,state())
                request(HttpMethod.Get,history,expected=403)
                request(HttpMethod.Post,history,body=body,key=UUID.randomUUID(),expected=403);assertEquals(left,state())
                assertTrue(request(HttpMethod.Get,"/api/v1/me/chats",expected=200).getValue("items").jsonArray.isEmpty())
                // The second point has exactly one member: a muted account may still leave.
                request(HttpMethod.Delete,"/api/v1/chats/${f.chats[1]}/membership",asActor=f.peer,expected=204)
                f.connection().use { c -> c.prepareStatement("SELECT count(*) FROM points WHERE id=?").use { s -> s.setObject(1,f.points[1]);s.executeQuery().use{r->assertTrue(r.next());assertEquals(0,r.getInt(1))} } }
                assertEquals(1,f.count("points"));assertEquals(1,f.count("chats"))
                request(HttpMethod.Get,"/api/v1/chats/${f.chats[1]}/messages",asActor=f.peer,expected=403)
            }
        }
        val destination=Path.of("build","m3-evidence","rest-chain.json")
        Files.createDirectories(destination.parent)
        Files.writeString(destination,buildJsonObject{
            put("case","T084 / M3.T6.B02");put("databaseSnapshotColumns",JsonArray(listOf("messages","lastMessageSeq","actorRateEvents","actorOutcomes","actorReadSeq","reportsAgainstPeer").map(::JsonPrimitive)))
            put("steps",JsonArray(trace));put("result","PASS")
        }.toString())
    }
}
