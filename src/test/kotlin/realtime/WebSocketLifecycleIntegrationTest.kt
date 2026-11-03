package com.example.realtime

import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WebSocketLifecycleIntegrationTest {
    @Test
    fun `real heartbeat expiry logout and abrupt transport loss retain session boundaries`() = runBlocking {
        val url=com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user=System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password=System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val actor=UUID.randomUUID()
        val ids=List(3){UUID.randomUUID()}
        DriverManager.getConnection(url,user,password).use{c ->
            c.autoCommit=false
            c.prepareStatement("""INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                VALUES (?,?,'test-hash','Lifecycle fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""").use{
                it.setObject(1,actor);it.setString(2,"+7"+actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10,'0'));it.executeUpdate()
            }
            for(id in ids)c.prepareStatement("""INSERT INTO auth_sessions (id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)""").use{
                it.setObject(1,id);it.setObject(2,actor);it.setString(3,id.toString().replace("-","").repeat(2));it.executeUpdate()
            }
            c.commit()
        }
        val secret="websocket-lifecycle-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url,user,password,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use{db ->
            lateinit var hub:RealtimeHub
            val repository=JdbcSessionRepository(db)
            val access=SessionAccessService(secret,repository,onSessionRevoked={hub.closeRevokedSession(it.sessionId)})
            hub=RealtimeHub(db,access)
            val server=embeddedServer(Netty,host="127.0.0.1",port=0){configureTransport();installRealtimeTransport();routing{realtimeRoutes(hub)}}
            val sockets=mutableListOf<WebSocket>()
            server.start(wait=false)
            try{
                val port=server.engine.resolvedConnectors().single().port
                val now=Instant.now()
                val tokens=ids.mapIndexed{index,id -> "Bearer "+JwtTokenIssuer(secret).issue(actor,id,0,now.minusSeconds(1),now.plusSeconds(if(index==0) 5 else 3600))}
                val client=HttpClient.newHttpClient()
                fun connect(index:Int,listener:WireListener)=client.newWebSocketBuilder().header("Authorization",tokens[index])
                    .buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"),listener).get(5,TimeUnit.SECONDS).also{sockets+=it}
                val expiring=WireListener()
                connect(0,expiring)
                assertEquals("hello",expiring.next()["type"]?.jsonPrimitive?.content)
                val survivor=WireListener()
                val survivingSocket=connect(1,survivor)
                val hello=survivor.next()
                assertEquals(30,hello.getValue("heartbeatSeconds").jsonPrimitive.int)
                val dropped=WireListener()
                connect(2,dropped).abort()
                // Abrupt client transport loss is not logout: the persisted identity can reconnect.
                assertEquals(ids[2],access.currentIdentity(tokens[2]).sessionId)
                val reconnected=WireListener()
                connect(2,reconnected)
                assertEquals("hello",reconnected.next()["type"]?.jsonPrimitive?.content)
                access.logout(tokens[2])
                assertEquals("session.revoked",reconnected.next()["type"]?.jsonPrimitive?.content)
                assertEquals(4401,reconnected.closed.get(5,TimeUnit.SECONDS))
                assertEquals(ids[1],access.currentIdentity(tokens[1]).sessionId)
                // No accelerated clock or test heartbeat: observe the actual configured 30-second ping.
                val pingAt=survivor.firstPing.get(40,TimeUnit.SECONDS)
                val elapsedMs=TimeUnit.NANOSECONDS.toMillis(pingAt-survivor.openedAt)
                assertTrue(elapsedMs in 28000..40000,"Actual server heartbeat outside documented tolerance: $elapsedMs ms")
                survivor.pongSent.get(5,TimeUnit.SECONDS)
                // Client->server Ping obtains a native server Pong too; both control directions execute.
                survivingSocket.sendPing(ByteBuffer.wrap(byteArrayOf(6,7))).get(5,TimeUnit.SECONDS)
                survivor.serverPong.get(5,TimeUnit.SECONDS)
                assertEquals(4401,expiring.closed.get(5,TimeUnit.SECONDS))
                assertTrue(expiring.closedAt.isAfter(now.plusSeconds(5)),"Auth close preceded signed JWT expiry")
                val expiryCloseMs=TimeUnit.NANOSECONDS.toMillis(expiring.closedNano-expiring.openedAt)
                assertTrue(expiryCloseMs in 4000..40000,"Auth watcher close outside bounded observation: $expiryCloseMs ms")
                val marker=UUID.randomUUID()
                hub.publishCommitted(RealtimeEvent(marker,RealtimeEventType.NOTIFICATION_CREATED,RealtimeScopeKind.USER,actor,Instant.now(),buildJsonObject{put("marker",marker.toString())}))
                assertEquals(marker.toString(),survivor.next().getValue("eventId").jsonPrimitive.content)
                assertNull(expiring.messages.poll(250,TimeUnit.MILLISECONDS),"Expired socket received a post-close event")
                assertNull(reconnected.messages.poll(250,TimeUnit.MILLISECONDS),"Revoked socket received a post-close event")
                assertEquals(ids[1],access.currentIdentity(tokens[1]).sessionId)
                db.withReadOnlyJdbcTransaction{c ->c.prepareStatement("SELECT id,revoked_at IS NOT NULL FROM auth_sessions WHERE user_id=?").use{
                    it.setObject(1,actor);it.executeQuery().use{r ->var count=0;while(r.next()){assertEquals(r.getObject(1,UUID::class.java)==ids[2],r.getBoolean(2));count++};assertEquals(3,count)}
                }}
                println("Observed native server heartbeat ${elapsedMs}ms; expiry close ${expiryCloseMs}ms; client Pong sent and server Pong received; expired/revoked close4401; other session live")
                survivingSocket.sendClose(WebSocket.NORMAL_CLOSURE,"fixture complete").get(5,TimeUnit.SECONDS)
                assertEquals(1000,survivor.closed.get(5,TimeUnit.SECONDS))
            }finally{
                sockets.forEach{it.abort()}
                server.stop(0,1000)
            }
        }
    }
    private class WireListener:WebSocket.Listener{
        val messages=LinkedBlockingQueue<JsonObject>()
        val firstPing=CompletableFuture<Long>()
        val pongSent=CompletableFuture<Void>()
        val serverPong=CompletableFuture<Void>()
        val closed=CompletableFuture<Int>()
        var closedAt=Instant.EPOCH
        var closedNano=0L
        var openedAt=0L
        private val text=StringBuilder()
        override fun onOpen(socket:WebSocket){openedAt=System.nanoTime();socket.request(1)}
        override fun onText(socket:WebSocket,data:CharSequence,last:Boolean):CompletionStage<*> {
            text.append(data)
            if(last){messages.add(Json.parseToJsonElement(text.toString()).jsonObject);text.setLength(0)}
            socket.request(1);return CompletableFuture.completedFuture(null)
        }
        override fun onPing(socket:WebSocket,message:ByteBuffer):CompletionStage<*> {
            firstPing.complete(System.nanoTime());socket.request(1)
            return socket.sendPong(message).thenRun{pongSent.complete(null)}
        }
        override fun onPong(socket:WebSocket,message:ByteBuffer):CompletionStage<*> {
            serverPong.complete(null);socket.request(1);return CompletableFuture.completedFuture(null)
        }
        override fun onClose(socket:WebSocket,statusCode:Int,reason:String):CompletionStage<*> {
            closedAt=Instant.now();closedNano=System.nanoTime();closed.complete(statusCode);return CompletableFuture.completedFuture(null)
        }
        override fun onError(socket:WebSocket,error:Throwable){closed.completeExceptionally(error);firstPing.completeExceptionally(error);serverPong.completeExceptionally(error)}
        fun next():JsonObject=assertNotNull(messages.poll(5,TimeUnit.SECONDS),"Expected native WebSocket text frame")
    }
}
