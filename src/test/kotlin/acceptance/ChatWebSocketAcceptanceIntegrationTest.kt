package com.example.acceptance

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.chats.ChatReadService
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.realtime.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ChatWebSocketAcceptanceIntegrationTest {
    @Test
    fun `socket isolation missed delta and concrete session revoke match persisted chat`() = runBlocking<Unit> {
        val destination=Path.of("build","m3-evidence","ws-isolation.json")
        Files.deleteIfExists(destination)
        val url=com.example.testing.testDatabaseUrl()
        val user=System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password=System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret="chat-ws-acceptance-secret-at-least-32-bytes"
        val trace=mutableListOf<JsonObject>()
        val fixture=ChatAcceptanceFixture(url,user,password)
        fixture.use { f ->
            HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use {db ->
                lateinit var hub:RealtimeHub
                val access=SessionAccessService(secret,JdbcSessionRepository(db),onSessionRevoked={hub.closeRevokedSession(it.sessionId)})
                hub=RealtimeHub(db,access)
                val server=embeddedServer(Netty,host="127.0.0.1",port=0){
                    configureTransport();installRealtimeTransport()
                    configureRouting(db,sessionAccess=access,chatReadService=ChatReadService(db,secret),chatSendService=ChatSendService(db,MessageContentPolicy()),realtimeHub=hub)
                }
                val sockets=mutableListOf<WebSocket>()
                server.start(wait=false)
                try {
                    val port=server.engine.resolvedConnectors().single().port
                    val client=HttpClient.newHttpClient()
                    val chat=f.chats[0]
                    fun state()=f.connection().use {c ->
                        c.prepareStatement("SELECT last_message_seq,(SELECT count(*) FROM messages WHERE chat_id=?) FROM chats WHERE id=?").use{
                            it.setObject(1,chat);it.setObject(2,chat);it.executeQuery().use{r->assertTrue(r.next());listOf(r.getLong(1),r.getLong(2))}
                        }
                    }
                    fun request(method:String,path:String,actor:UUID,body:JsonObject?=null,key:UUID?=null,expected:Int):JsonObject {
                        val id=UUID.randomUUID()
                        val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Authorization",f.bearer(actor,secret)).header("X-Request-Id",id.toString())
                        key?.let{builder.header("Idempotency-Key",it.toString())}
                        if(body!=null)builder.header("Content-Type","application/json")
                        val response=client.send(builder.method(method,if(body==null)HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString())
                        assertEquals(expected,response.statusCode());assertEquals(id.toString(),response.headers().firstValue("X-Request-Id").orElse(null))
                        val parsed=Json.parseToJsonElement(response.body()).jsonObject
                        trace+=buildJsonObject{put("kind","REST");put("requestId",id.toString());put("actorId",actor.toString());put("method",method);put("path",path);put("status",response.statusCode());put("dbSnapshot",JsonArray(state().map(::JsonPrimitive)));put("messageId",parsed["id"]?:JsonNull);put("messageSeq",parsed["messageSeq"]?:JsonNull);put("items",JsonArray(parsed["items"]?.jsonArray?.map{item->buildJsonObject{put("messageId",item.jsonObject.getValue("id"));put("messageSeq",item.jsonObject.getValue("messageSeq"))}}?:emptyList()))}
                        return parsed
                    }
                    fun connect(token:String):Pair<WebSocket,Wire> {
                        val wire=Wire()
                        val socket=client.newWebSocketBuilder().header("Authorization",token).buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"),wire).get(5,TimeUnit.SECONDS)
                        sockets+=socket;assertEquals("hello",wire.next()["type"]?.jsonPrimitive?.content)
                        return socket to wire
                    }
                    fun subscribe(socket:WebSocket,wire:Wire,chatId:UUID) {
                        val id=UUID.randomUUID()
                        socket.sendText(buildJsonObject{put("v",1);put("type","subscribe");put("requestId",id.toString());put("chatIds",JsonArray(listOf(JsonPrimitive(chatId.toString()))))}.toString(),true).get(5,TimeUnit.SECONDS)
                        val result=wire.next();assertEquals(id.toString(),result["requestId"]?.jsonPrimitive?.content);assertEquals(chatId.toString(),result["acceptedChatIds"]!!.jsonArray.single().jsonPrimitive.content)
                        trace+=buildJsonObject{put("kind","subscribe");put("requestId",id.toString());put("chatId",chatId.toString());put("responseType",result["type"]!!)}
                    }
                    fun event(wire:Wire,message:JsonObject):JsonObject {
                        val observed=wire.next()
                        assertEquals(1,observed["v"]?.jsonPrimitive?.int);assertEquals("chat.message.created",observed["type"]?.jsonPrimitive?.content)
                        assertEquals(buildJsonObject{put("kind","chat");put("id",chat.toString())},observed["scope"])
                        assertEquals(message,observed["payload"])
                        val eventId=observed.getValue("eventId").jsonPrimitive.content;assertEquals(eventId,UUID.fromString(eventId).toString())
                        trace+=buildJsonObject{put("kind","WS");put("eventId",eventId);put("scope",observed.getValue("scope"));put("messageId",message.getValue("id"));put("messageSeq",message.getValue("messageSeq"));put("dbSnapshot",JsonArray(state().map(::JsonPrimitive)))}
                        return observed
                    }
                    suspend fun barrier(actor:UUID,vararg wires:Wire) {
                        val id=UUID.randomUUID()
                        hub.publishCommitted(RealtimeEvent(id,RealtimeEventType.NOTIFICATION_CREATED,RealtimeScopeKind.USER,actor,Instant.now(),buildJsonObject{put("marker",id.toString())}))
                        wires.forEach{it.noMessageThrough(id)}
                        trace+=buildJsonObject{put("kind","isolationBarrier");put("eventId",id.toString());put("actorId",actor.toString());put("observedSockets",wires.size);put("postSentinelMillis",250)}
                    }
                    val memberToken=f.bearer(f.member,secret)
                    val (memberSocket,memberWire)=connect(memberToken);subscribe(memberSocket,memberWire,chat)
                    val (peerSocket,peerWire)=connect(f.bearer(f.peer,secret));subscribe(peerSocket,peerWire,chat)
                    val (adjacentSocket,adjacentWire)=connect(f.bearer(f.peer,secret));subscribe(adjacentSocket,adjacentWire,f.chats[1])
                    val first=request("POST","/api/v1/chats/$chat/messages",f.member,buildJsonObject{put("text","Synthetic WS first")},UUID.randomUUID(),201)
                    assertEquals(3L,first.getValue("messageSeq").jsonPrimitive.long)
                    val firstEvent=event(memberWire,first);assertEquals(firstEvent["eventId"],event(peerWire,first)["eventId"])
                    barrier(f.peer,peerWire,adjacentWire)
                    memberSocket.abort()
                    assertEquals(f.sessions.getValue(f.member),access.currentIdentity(memberToken).sessionId)
                    trace+=buildJsonObject{put("kind","transportAbort");put("sessionId",f.sessions.getValue(f.member).toString());put("authenticatedAfterAbort",true)}
                    val missed=request("POST","/api/v1/chats/$chat/messages",f.peer,buildJsonObject{put("text","Synthetic WS missed")},UUID.randomUUID(),201)
                    assertEquals(4L,missed.getValue("messageSeq").jsonPrimitive.long);event(peerWire,missed)
                    val delta=request("GET","/api/v1/chats/$chat/messages?afterSeq=3",f.member,expected=200).getValue("items").jsonArray
                    assertEquals(listOf(missed),delta.toList())
                    f.connection().use{c->c.prepareStatement("SELECT id,message_seq FROM messages WHERE chat_id=? AND message_seq>3 ORDER BY message_seq").use{
                        it.setObject(1,chat);it.executeQuery().use{r->assertTrue(r.next());assertEquals(missed.getValue("id").jsonPrimitive.content,r.getObject(1).toString());assertEquals(4L,r.getLong(2));assertFalse(r.next())}
                    }}
                    val (reconnectedSocket,reconnectedWire)=connect(memberToken);subscribe(reconnectedSocket,reconnectedWire,chat)
                    val future=request("POST","/api/v1/chats/$chat/messages",f.reporters[0],buildJsonObject{put("text","Synthetic WS after reconnect")},UUID.randomUUID(),201)
                    assertEquals(5L,future.getValue("messageSeq").jsonPrimitive.long);event(peerWire,future);event(reconnectedWire,future)
                    barrier(f.peer,peerWire,adjacentWire)
                    val secondId=UUID.randomUUID()
                    f.transaction{c->c.prepareStatement("INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)").use{
                        it.setObject(1,secondId);it.setObject(2,f.member);it.setString(3,secondId.toString().replace("-","").repeat(2));assertEquals(1,it.executeUpdate())
                    }}
                    val now=Instant.now()
                    val secondToken="Bearer "+JwtTokenIssuer(secret).issue(f.member,secondId,0,now.minusSeconds(1),now.plusSeconds(3600))
                    val (secondSocket,secondWire)=connect(secondToken);subscribe(secondSocket,secondWire,chat)
                    access.logout(memberToken)
                    val revoked=reconnectedWire.next();assertEquals("session.revoked",revoked["type"]?.jsonPrimitive?.content)
                    assertEquals(4401,reconnectedWire.closed.get(5,TimeUnit.SECONDS))
                    assertEquals(secondId,access.currentIdentity(secondToken).sessionId)
                    assertFailsWith<com.example.api.ApiException>{access.currentIdentity(memberToken)}
                    val last=request("POST","/api/v1/chats/$chat/messages",f.reporters[1],buildJsonObject{put("text","Synthetic WS surviving session")},UUID.randomUUID(),201)
                    assertEquals(6L,last.getValue("messageSeq").jsonPrimitive.long);event(peerWire,last);event(secondWire,last)
                    barrier(f.peer,peerWire,adjacentWire)
                    assertNull(reconnectedWire.messages.poll(250,TimeUnit.MILLISECONDS),"Revoked session received later event")
                    trace+=buildJsonObject{put("kind","sessionRevoked");put("sessionId",f.sessions.getValue(f.member).toString());put("survivingSessionId",secondId.toString());put("closeCode",4401);put("missedRecoveredSeq",4);put("futureLiveSeq",6);put("dbSnapshot",JsonArray(state().map(::JsonPrimitive)))}
                }finally{sockets.forEach{it.abort()};server.stop(0,1000)}
            }
        }
        for(table in listOf("users","points","chats","messages"))assertEquals(0,fixture.count(table))
        val schemaVersion=fixture.connection().use{c->c.createStatement().use{it.executeQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1").use{r->assertTrue(r.next());r.getString(1)}}}
        Files.createDirectories(destination.parent)
        Files.writeString(destination,buildJsonObject{put("case","T085 / M3.T6.B03");put("schemaVersion",schemaVersion);put("trace",JsonArray(trace));put("fixtureCleanup","scoped users/points/chats/messages all zero");put("isolationBound","ordered sentinel plus 250ms; not unbounded exactly-once")}.toString())
    }
    private class Wire:WebSocket.Listener {
        val messages=LinkedBlockingQueue<JsonObject>()
        val closed=CompletableFuture<Int>()
        private val buffer=StringBuilder()
        override fun onOpen(socket:WebSocket){socket.request(1)}
        override fun onText(socket:WebSocket,data:CharSequence,last:Boolean):CompletionStage<*> {
            buffer.append(data);if(last){messages.add(Json.parseToJsonElement(buffer.toString()).jsonObject);buffer.setLength(0)}
            socket.request(1);return CompletableFuture.completedFuture(null)
        }
        override fun onClose(socket:WebSocket,code:Int,reason:String):CompletionStage<*>{closed.complete(code);return CompletableFuture.completedFuture(null)}
        override fun onError(socket:WebSocket,error:Throwable){closed.completeExceptionally(error)}
        fun next()=assertNotNull(messages.poll(5,TimeUnit.SECONDS),"Expected native WS frame")
        fun noMessageThrough(marker:UUID){
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(true){assertTrue(System.nanoTime()<deadline,"Sentinel deadline");val frame=next();assertNotEquals("chat.message.created",frame["type"]?.jsonPrimitive?.content);if(frame["eventId"]?.jsonPrimitive?.content==marker.toString())break}
            assertNull(messages.poll(250,TimeUnit.MILLISECONDS),"Unexpected frame after isolation sentinel")
        }
    }
}
