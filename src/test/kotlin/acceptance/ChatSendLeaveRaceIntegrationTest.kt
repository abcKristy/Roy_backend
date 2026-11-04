package com.example.acceptance

import com.example.api.*
import com.example.auth.SessionAccessService
import com.example.chats.*
import com.example.config.*
import com.example.persistence.*
import com.example.points.PointWriteService
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ChatSendLeaveRaceIntegrationTest {
    @Test fun `send holding actor commits before overlapping leave`(): Unit = runOrder(true)
    @Test fun `leave holding actor revokes overlapping send`(): Unit = runOrder(false)

    @Test fun `last member leave deletes chat before overlapping send`(): Unit = runOrder(false, true)

    private fun runOrder(sendFirst: Boolean, lastMember: Boolean = false): Unit = testApplication {
        val url=com.example.testing.testDatabaseUrl()
        val user=System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password=System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret="race-acceptance-secret-at-least-32-bytes"
        val trace=mutableListOf<JsonObject>()
        ChatAcceptanceFixture(url,user,password).use { f ->
            val index=if(lastMember)1 else 0
            val actor=if(lastMember)f.peer else f.member;val chat=f.chats[index];val point=f.points[index];val key=UUID.randomUUID()
            HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION,
                databaseStatementTimeoutMs=15000,databaseLockTimeoutMs=12000)).start().use { db ->
                application { configureTransport(); configureRouting(db,
                    sessionAccess=SessionAccessService(secret,JdbcSessionRepository(db)),pointWriteService=PointWriteService(db),
                    chatReadService=ChatReadService(db,secret),chatSendService=ChatSendService(db,MessageContentPolicy())) }
                startApplication()
                val clients=List(2){createClient{}}
                suspend fun request(send:Boolean):Pair<Int,JsonElement> {
                    val requestId=UUID.randomUUID().toString()
                    val response=clients[if(send)0 else 1].request("/api/v1/chats/$chat/"+if(send)"messages" else "membership") {
                        method=if(send)HttpMethod.Post else HttpMethod.Delete
                        header(HttpHeaders.Authorization,f.bearer(actor,secret));header("X-Request-Id",requestId)
                        if(send){header("Idempotency-Key",key.toString());contentType(ContentType.Application.Json);setBody("{\"text\":\"Synthetic race text\"}")}
                    }
                    assertEquals(requestId,response.headers["X-Request-Id"])
                    val raw=response.bodyAsText();val body=if(raw.isEmpty())JsonNull else Json.parseToJsonElement(raw)
                    synchronized(trace){trace+=buildJsonObject{put("operation",if(send)"send" else "leave");put("requestId",requestId);put("key",if(send)JsonPrimitive(key.toString()) else JsonNull);put("status",response.status.value);put("body",body)}}
                    return response.status.value to body
                }
                fun snapshot():JsonObject=f.connection().use { c ->
                    fun scalar(sql:String,id:UUID):Long=c.prepareStatement(sql).use{s->s.setObject(1,id);s.executeQuery().use{r->assertTrue(r.next());r.getLong(1)}}
                    buildJsonObject {
                        put("messages",scalar("SELECT count(*) FROM messages WHERE chat_id=?",chat))
                        put("distinctSeq",scalar("SELECT count(DISTINCT message_seq) FROM messages WHERE chat_id=?",chat))
                        put("lastSeq",scalar("SELECT coalesce(max(last_message_seq),0) FROM chats WHERE id=?",chat))
                        put("rate",scalar("SELECT count(*) FROM message_rate_events WHERE user_id=?",actor))
                        put("outcomes",scalar("SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?",actor))
                        put("left",scalar("SELECT count(*) FROM chat_memberships WHERE user_id=? AND left_at IS NOT NULL",actor))
                    }
                }
                suspend fun waitBlockedBy(blockerPid:Int):Int=withTimeout(6000) {
                    while(true){
                        val found=f.connection().use { c -> c.prepareStatement("SELECT pid FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)) ORDER BY pid").use{s->s.setInt(1,blockerPid);s.executeQuery().use{r->if(r.next())r.getInt(1) else null}} }
                        if(found!=null)return@withTimeout found
                        delay(10)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                }
                val before=snapshot()
                assertEquals(2L,before.getValue("messages").jsonPrimitive.long)
                val results=f.connection().use { blocker ->
                    blocker.autoCommit=false
                    blocker.prepareStatement("SELECT id FROM points WHERE id=? FOR UPDATE").use{s->s.setObject(1,point);s.executeQuery().use{assertTrue(it.next())}}
                    val pid=blocker.createStatement().use{s->s.executeQuery("SELECT pg_backend_pid()").use{r->r.next();r.getInt(1)}}
                    try { coroutineScope {
                        val first=async(Dispatchers.Default){request(sendFirst)}
                        val firstPid=waitBlockedBy(pid)
                        val second=async(Dispatchers.Default){request(!sendFirst)}
                        val secondPid=waitBlockedBy(firstPid)
                        assertNotEquals(firstPid,secondPid);assertFalse(first.isCompleted);assertFalse(second.isCompleted)
                        // This observed lock chain proves both HTTP commands overlap before release.
                        synchronized(trace){trace+=buildJsonObject{put("barrier","point-holder -> first-command -> second-command");put("pointHolderPid",pid);put("firstCommandPid",firstPid);put("secondCommandPid",secondPid);put("firstOperation",if(sendFirst)"send" else "leave");put("bothIncomplete",true)}}
                        blocker.commit()
                        withTimeout(15000){first.await() to second.await()}
                    } } finally { blocker.rollback() }
                }
                val send=if(sendFirst)results.first else results.second
                val leave=if(sendFirst)results.second else results.first
                assertEquals(204,leave.first);assertEquals(if(sendFirst)201 else 403,send.first)
                if(!sendFirst)assertEquals("NOT_MEMBER",send.second.jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                val after=snapshot();val expectedSeq=if(lastMember)0L else if(sendFirst)4L else 3L
                assertEquals(expectedSeq,after.getValue("messages").jsonPrimitive.long)
                assertEquals(expectedSeq,after.getValue("distinctSeq").jsonPrimitive.long)
                assertEquals(expectedSeq,after.getValue("lastSeq").jsonPrimitive.long)
                assertEquals(if(sendFirst)1L else 0L,after.getValue("rate").jsonPrimitive.long)
                assertEquals(if(sendFirst)1L else 0L,after.getValue("outcomes").jsonPrimitive.long)
                assertEquals(if(lastMember)0L else 1L,after.getValue("left").jsonPrimitive.long)
                val replay=request(true);assertEquals(send.first,replay.first)
                if(sendFirst)assertEquals(send.second,replay.second) else assertEquals("NOT_MEMBER",replay.second.jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(after,snapshot())
                assertEquals(if(lastMember)404 else 204,request(false).first);assertEquals(after,snapshot())
                val denied=client.get("/api/v1/chats/$chat/messages"){header(HttpHeaders.Authorization,f.bearer(actor,secret))}
                assertEquals(HttpStatusCode.Forbidden,denied.status)
                val history=client.get("/api/v1/chats/$chat/messages"){header(HttpHeaders.Authorization,f.bearer(f.peer,secret))}
                val historyBody=Json.parseToJsonElement(history.bodyAsText())
                if(lastMember) {
                    assertEquals(HttpStatusCode.Forbidden,history.status)
                    f.connection().use { c ->
                        for(table in listOf("points","chats","messages","chat_memberships")) {
                            val column=if(table in listOf("points","chats"))"id" else "chat_id"
                            c.prepareStatement("SELECT count(*) FROM $table WHERE $column=?").use { s ->
                                s.setObject(1,if(table=="points")point else chat);s.executeQuery().use{r->assertTrue(r.next());assertEquals(0,r.getInt(1))}
                            }
                        }
                    }
                    assertEquals(1,f.count("points"));assertEquals(1,f.count("chats"));assertEquals(2,f.count("messages"))
                    val survivor=client.get("/api/v1/chats/${f.chats[0]}/messages"){header(HttpHeaders.Authorization,f.bearer(f.member,secret))}
                    assertEquals(HttpStatusCode.OK,survivor.status)
                    assertEquals(2,Json.parseToJsonElement(survivor.bodyAsText()).jsonObject.getValue("items").jsonArray.size)
                } else {
                    assertEquals(HttpStatusCode.OK,history.status)
                    val items=historyBody.jsonObject.getValue("items").jsonArray
                    assertEquals((expectedSeq downTo 1L).toList(),items.map{it.jsonObject.getValue("messageSeq").jsonPrimitive.long})
                    assertMembershipSystemMessage(items[0].jsonObject,point,chat,actor,"member_left")
                    if(sendFirst){assertEquals(send.second,items[1]);assertEquals(3L,send.second.jsonObject.getValue("messageSeq").jsonPrimitive.long)}
                }
                assertEquals(after,snapshot())
                synchronized(trace){trace+=buildJsonObject{put("before",before);put("after",after);put("actorHistoryStatus",denied.status.value);put("peerHistoryStatus",history.status.value);put("peerHistory",historyBody)}}
            }
        }
        val path=Path.of("build","m3-evidence",if(lastMember)"send-leave-last-member.json" else if(sendFirst)"send-leave-send-first.json" else "send-leave-leave-first.json")
        Files.createDirectories(path.parent)
        Files.writeString(path,buildJsonObject{put("case","T086 / M3.T6.B04");put("sendFirst",sendFirst);put("lastMember",lastMember);put("steps",JsonArray(trace));put("result","PASS")}.toString())
        Unit
    }
}
