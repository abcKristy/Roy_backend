package com.example.realtime

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.points.JoinPointChatResult
import com.example.points.LeavePointChatResult
import com.example.points.PointWriteService
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.sql.DriverManager
import java.time.Instant
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.test.*

class WebSocketSubscriptionIntegrationTest {
    @Test
    fun `real database sessions scope subscriptions leave and individual revocation`() = testApplication {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val actor = UUID.randomUUID()
        val neighbor = UUID.randomUUID()
        val sessionIds = List(3) { UUID.randomUUID() }
        val point = UUID.randomUUID()
        val chats = List(2) { UUID.randomUUID() }
        DriverManager.getConnection(url, dbUser, dbPassword).use { c ->
            c.autoCommit = false
            for (id in listOf(actor, neighbor)) {
                c.prepareStatement("""INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                    accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'test-hash','Socket fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""").use {
                    it.setObject(1,id); it.setString(2,"+7"+id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10,'0')); it.executeUpdate()
                }
            }
            for ((index,id) in sessionIds.withIndex()) {
                c.prepareStatement("""INSERT INTO auth_sessions (id,user_id,created_at,expires_at,auth_version,refresh_token_hash)
                    VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)""").use {
                    it.setObject(1,id); it.setObject(2,if(index<2) actor else neighbor)
                    it.setString(3,id.toString().replace("-","").repeat(2)); it.executeUpdate()
                }
            }
            c.prepareStatement("""INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Socket fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""").use {
                it.setObject(1,point); it.setObject(2,actor); it.executeUpdate()
            }
            for ((index,chat) in chats.withIndex()) {
                val topic=UUID.randomUUID()
                c.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())").use {
                    it.setObject(1,topic); it.setObject(2,point); it.setString(3,if(index==0) "football" else "basketball"); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1,chat); it.setObject(2,topic); it.executeUpdate()
                }
                // A second member keeps the actor's old chat alive after leave.
                for(id in if(index==0) listOf(actor,neighbor) else listOf(neighbor)) {
                    c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                        it.setObject(1,chat); it.setObject(2,id); it.executeUpdate()
                    }
                }
            }
            c.commit()
        }
        val secret="socket-subscription-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url,dbUser,dbPassword,secret,appEnvironment=AppEnvironment.PRODUCTION)).start().use { db ->
            lateinit var hub: RealtimeHub
            val access=SessionAccessService(secret,JdbcSessionRepository(db),onSessionRevoked={ hub.closeRevokedSession(it.sessionId) })
            hub=RealtimeHub(db,access)
            val memberships = PointWriteService(db)
            application {
                configureTransport(); installRealtimeTransport()
                configureRouting(db,sessionAccess=access,pointWriteService=memberships,realtimeHub=hub)
            }
            val now=Instant.now()
            val tokens=sessionIds.mapIndexed { index,id -> "Bearer "+JwtTokenIssuer(secret).issue(if(index<2) actor else neighbor,id,0,now.minusSeconds(1),now.plusSeconds(3600)) }
            val ws=createClient { install(WebSockets) }
            // Exercise verification on live claims; an unknown JTI would mask a signature bypass.
            val expiresAt = now.plusSeconds(3600)
            val forged = "Bearer " + JwtTokenIssuer("different-signing-key-for-b6-fixture-only")
                .issue(actor, sessionIds[0], 0, now.minusSeconds(1), expiresAt)
            val unsigned = "Bearer " + JWT.create()
                .withSubject(actor.toString())
                .withJWTId(sessionIds[0].toString())
                .withClaim("authVersion", 0)
                .withIssuedAt(Date.from(now.minusSeconds(1)))
                .withExpiresAt(Date.from(expiresAt))
                .sign(Algorithm.none())
            val expired = "Bearer " + JwtTokenIssuer(secret)
                .issue(actor, sessionIds[0], 0, now.minusSeconds(3600), now.minusSeconds(1))
            suspend fun assertNativeAccess(denials: List<Pair<String?, String>>, validToken: String) {
                val native = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    configureTransport()
                    installRealtimeTransport()
                    configureRouting(db, sessionAccess = access, realtimeHub = hub)
                }
                native.start(wait = false)
                try {
                    val port = native.engine.resolvedConnectors().single().port
                    val client = HttpClient.newHttpClient()
                    fun requestMe(token: String?): HttpResponse<String> {
                        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/me")).GET()
                        token?.let { request.header(HttpHeaders.Authorization, it) }
                        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                    }
                    assertEquals(200, requestMe(validToken).statusCode())
                    for ((token, code) in denials) {
                        val response = requestMe(token)
                        assertEquals(401, response.statusCode())
                        val error = Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonObject
                        assertEquals(code, error.getValue("code").jsonPrimitive.content)
                        val builder = client.newWebSocketBuilder()
                        token?.let { builder.header(HttpHeaders.Authorization, it) }
                        val failure = assertFailsWith<ExecutionException> {
                            builder.buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"), object : WebSocket.Listener {})
                                .get(5, TimeUnit.SECONDS)
                        }
                        assertEquals(401, assertIs<WebSocketHandshakeException>(failure.cause).response.statusCode())
                    }
                } finally {
                    native.stop(0, 1000)
                }
            }
            assertNativeAccess(listOf(
                null to "AUTH_REQUIRED",
                expired to "SESSION_EXPIRED",
                forged to "SESSION_EXPIRED",
                unsigned to "SESSION_EXPIRED",
            ), tokens[0])
            ws.webSocket("/api/v1/ws",request={header(HttpHeaders.Authorization,tokens[0])}) {
                val first=this
                assertEquals("hello",next()["type"]?.jsonPrimitive?.content)
                val requestId=UUID.randomUUID()
                val nonexistent=UUID.randomUUID()
                command("subscribe",chats+chats[0]+nonexistent,requestId)
                val subscribed=next()
                assertEquals("subscribed",subscribed["type"]?.jsonPrimitive?.content)
                assertEquals(requestId.toString(),subscribed["requestId"]?.jsonPrimitive?.content)
                assertEquals(listOf(chats[0].toString()),subscribed["acceptedChatIds"]!!.jsonArray.map{it.jsonPrimitive.content})
                assertEquals(listOf(chats[1].toString(),nonexistent.toString()),subscribed["rejectedChatIds"]!!.jsonArray.map{it.jsonPrimitive.content})
                // Synthetic committed-scope probes exercise the production hub, not message publication.
                suspend fun probe(scope: RealtimeScopeKind,id:UUID,marker:UUID) = hub.publishCommitted(RealtimeEvent(marker,if(scope==RealtimeScopeKind.CHAT) RealtimeEventType.CHAT_MESSAGE_CREATED else RealtimeEventType.NOTIFICATION_CREATED,scope,id,Instant.now(),buildJsonObject{put("marker",marker.toString())}))
                val foreign=UUID.randomUUID(); probe(RealtimeScopeKind.CHAT,chats[1],foreign)
                val own=UUID.randomUUID(); probe(RealtimeScopeKind.CHAT,chats[0],own)
                marker(own,setOf(foreign))
                command("unsubscribe",listOf(chats[0]))
                assertEquals("unsubscribed",next()["type"]?.jsonPrimitive?.content)
                val unsubscribed=UUID.randomUUID(); probe(RealtimeScopeKind.CHAT,chats[0],unsubscribed)
                val sentinel=UUID.randomUUID(); probe(RealtimeScopeKind.USER,actor,sentinel)
                marker(sentinel,setOf(foreign,unsubscribed))
                command("subscribe",listOf(chats[0])); assertEquals("subscribed",next()["type"]?.jsonPrimitive?.content)
                ws.webSocket("/api/v1/ws",request={header(HttpHeaders.Authorization,tokens[1])}) {
                    val second=this
                    assertEquals("hello",next()["type"]?.jsonPrimitive?.content)
                    command("subscribe",listOf(chats[0])); assertEquals("subscribed",next()["type"]?.jsonPrimitive?.content)

                    // Delay leave publication until both membership transactions have committed.
                    assertIs<LeavePointChatResult.Left>(memberships.leave(actor, chats[0]))
                    assertIs<JoinPointChatResult.Joined>(memberships.join(actor, chats[0]))
                    second.command("subscribe", listOf(chats[0]))
                    assertEquals(listOf(chats[0].toString()), second.next()["acceptedChatIds"]!!.jsonArray.map { it.jsonPrimitive.content })
                    hub.publishMembershipChanged(chats[0], actor, "left")
                    val rejoined = UUID.randomUUID()
                    probe(RealtimeScopeKind.CHAT, chats[0], rejoined)
                    val afterRejoin = UUID.randomUUID()
                    probe(RealtimeScopeKind.USER, actor, afterRejoin)
                    first.marker(afterRejoin, setOf(rejoined))
                    second.marker(rejoined, emptySet())
                    second.marker(afterRejoin, emptySet())

                    assertEquals(HttpStatusCode.NoContent,client.delete("/api/v1/chats/${chats[0]}/membership"){header(HttpHeaders.Authorization,tokens[0])}.status)
                    val afterLeave=UUID.randomUUID(); probe(RealtimeScopeKind.CHAT,chats[0],afterLeave)
                    val alive=UUID.randomUUID(); probe(RealtimeScopeKind.USER,actor,alive)
                    first.marker(alive,setOf(afterLeave)); second.marker(alive,setOf(afterLeave))
                    command("subscribe",listOf(chats[0])); assertEquals(chats[0].toString(),next()["rejectedChatIds"]!!.jsonArray.single().jsonPrimitive.content)
                    access.logout(tokens[0])
                    assertEquals("session.revoked",first.next()["type"]?.jsonPrimitive?.content)
                    assertEquals(4401,withTimeout(5000){first.closeReason.await()}?.code?.toInt())
                    assertNativeAccess(listOf(tokens[0] to "SESSION_EXPIRED"), tokens[1])
                    val surviving=UUID.randomUUID(); probe(RealtimeScopeKind.USER,actor,surviving)
                    second.marker(surviving,emptySet())
                    assertEquals(sessionIds[1],access.currentIdentity(tokens[1]).sessionId)
                    db.withReadOnlyJdbcTransaction { c ->
                        c.prepareStatement("SELECT id,revoked_at IS NOT NULL FROM auth_sessions WHERE user_id=? ORDER BY id").use {
                            it.setObject(1,actor); it.executeQuery().use { rows ->
                                var count=0
                                while(rows.next()){assertEquals(rows.getObject(1,UUID::class.java)==sessionIds[0],rows.getBoolean(2));count++}
                                assertEquals(2,count)
                            }
                        }
                    }
                }
            }
        }
    }
    private suspend fun DefaultClientWebSocketSession.next():JsonObject=withTimeout(5000){
        var frame=incoming.receive()
        while(frame !is Frame.Text) frame=incoming.receive()
        Json.parseToJsonElement(frame.readText()).jsonObject
    }
    private suspend fun DefaultClientWebSocketSession.command(type:String,chats:List<UUID>,requestId:UUID=UUID.randomUUID()){
        send(Frame.Text(buildJsonObject{put("v",1);put("type",type);put("requestId",requestId.toString());put("chatIds",JsonArray(chats.map{JsonPrimitive(it.toString())}))}.toString()))
    }
    private suspend fun DefaultClientWebSocketSession.marker(expected:UUID,forbidden:Set<UUID>)=withTimeout(5000){
        while(true){
            val event=next(); val id=event["eventId"]?.jsonPrimitive?.content
            assertFalse(id in forbidden.map(UUID::toString),"Forbidden scope probe delivered")
            if(id==expected.toString()) break
        }
    }
}
