package com.example.meetings

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import com.example.testing.testDatabaseUrl
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import com.example.realtime.*
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class BookingPairwisePriorityIntegrationTest {
    @Test
    fun `native canonical priority is pairwise and survives cancellation plus rebooking with immutable order`() = runBlocking<Unit> {
        Fixture().use { f ->
            val a=f.seedBooking(f.chats[0],0,10,booking=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff1001"))
            val b=f.seedBooking(f.chats[1],5,15,booking=UUID.fromString("00000000-0000-0000-0000-000000001002"))
            val c=f.seedBooking(f.chats[0],10,20,booking=UUID.fromString("88888888-8888-8888-8888-888888881003"))
            val adjacent=f.seedBooking(f.chats[0],20,30)
            val cancelled=f.seedBooking(f.chats[0],0,30,cancelled=true)
            val otherPoint=f.seedBooking(f.chats[2],0,30)
            assertEquals(a.bookedAt,b.bookedAt); assertEquals(b.bookedAt,c.bookedAt)
            assertTrue(a.bookingOrder<b.bookingOrder && b.bookingOrder<c.bookingOrder)
            assertTrue(a.bookingId.toString()>c.bookingId.toString(),"IDs deliberately oppose order for B's list")
            Api(f).use { api ->
                fun canonical(value:BookingConflictView,expected:List<BookingConflictView>, status:String="active"): JsonObject {
                    val before=f.snapshot()
                    val detail=api.get("/api/v1/proposals/${value.proposalId}")
                    val booking=detail.getValue("booking").jsonObject
                    assertEquals(value.bookingId.toString(),booking.getValue("id").jsonPrimitive.content)
                    assertEquals(value.bookingOrder,booking.getValue("bookingOrder").jsonPrimitive.long)
                    assertEquals(value.bookedAt.toString(),booking.getValue("bookedAt").jsonPrimitive.content)
                    assertEquals(status,booking.getValue("status").jsonPrimitive.content)
                    assertEquals(JsonArray(expected.map(::conflictJson)),booking.getValue("overlappingActive"))
                    val chat=detail.getValue("chatId").jsonPrimitive.content
                    val page=api.get("/api/v1/chats/$chat/proposals")
                    assertEquals(detail,page.getValue("items").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content==value.proposalId.toString() })
                    assertEquals(before,f.snapshot(),"Canonical GET/list reads must not change priority or effects")
                    return detail
                }
                canonical(a,listOf(b)); canonical(b,listOf(a,c)); canonical(c,listOf(b))
                canonical(adjacent,emptyList()); canonical(cancelled,emptyList(),"cancelled"); canonical(otherPoint,emptyList())
                val cancelledResponse=api.vote(b.proposalId,false)
                assertEquals("cancelled",cancelledResponse.getValue("booking").jsonObject.getValue("status").jsonPrimitive.content)
                api.bookingEvent(b.proposalId,b.bookingId,"cancelled","insufficient_votes",4)
                canonical(a,emptyList()); canonical(c,emptyList()); canonical(b,emptyList(),"cancelled")
                canonical(adjacent,emptyList()); canonical(otherPoint,emptyList())
                val afterCancel=f.snapshot()
                assertEquals(cancelledResponse,api.vote(b.proposalId,false))
                assertEquals(afterCancel,f.snapshot())
                api.silence()
                val ready=api.vote(b.proposalId,true)
                assertEquals(5L,ready.getValue("revision").jsonPrimitive.long)
                api.proposalEvent(b.proposalId,5)
                val key=UUID.randomUUID()
                val created=api.book(b.proposalId,key,5)
                val rebooked=created.getValue("booking").jsonObject
                val replacement=BookingConflictView(UUID.fromString(rebooked.getValue("id").jsonPrimitive.content),b.proposalId,b.startsAt,b.endsAt,
                    Instant.parse(rebooked.getValue("bookedAt").jsonPrimitive.content),rebooked.getValue("bookingOrder").jsonPrimitive.long)
                assertNotEquals(b.bookingId,replacement.bookingId)
                assertTrue(replacement.bookingOrder>otherPoint.bookingOrder)
                assertEquals(JsonArray(listOf(a,c).map(::conflictJson)),rebooked.getValue("overlappingActive"))
                api.bookingEvent(b.proposalId,replacement.bookingId,"active",null,6)
                canonical(a,listOf(replacement)); canonical(c,listOf(replacement)); canonical(replacement,listOf(a,c))
                canonical(adjacent,emptyList()); canonical(cancelled,emptyList(),"cancelled"); canonical(otherPoint,emptyList())
                val beforeReplay=f.snapshot()
                assertEquals(created,api.book(b.proposalId,key,5))
                assertEquals(beforeReplay,f.snapshot()); api.silence()
            }
        }
    }
    private fun conflictJson(v:BookingConflictView)=buildJsonObject {
        put("bookingId",v.bookingId.toString()); put("proposalId",v.proposalId.toString())
        put("startsAt",v.startsAt.toString()); put("endsAt",v.endsAt.toString())
        put("bookedAt",v.bookedAt.toString()); put("bookingOrder",v.bookingOrder)
    }
    private class Api(private val f:Fixture):AutoCloseable {
        private val access=SessionAccessService(f.secret,JdbcSessionRepository(f.runtime))
        private val hub=RealtimeHub(f.runtime,access)
        private val server=embeddedServer(Netty,host="127.0.0.1",port=0) {
            configureTransport(); installRealtimeTransport()
            configureRouting(f.runtime,sessionAccess=access,realtimeHub=hub,
                proposalService=ProposalService(f.runtime,f.secret),bookingService=BookingService(f.runtime))
        }
        private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val port:Int
        private val wire=Wire()
        private val socket:WebSocket
        init {
            server.start(wait=false); port=runBlocking { server.engine.resolvedConnectors().single().port }
            socket=client.newWebSocketBuilder().header("Authorization",f.bearer())
                .buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"),wire).get(5,TimeUnit.SECONDS)
            assertEquals("hello",wire.next().getValue("type").jsonPrimitive.content)
            val request=UUID.randomUUID()
            socket.sendText(buildJsonObject { put("v",1);put("type","subscribe");put("requestId",request.toString());put("chatIds",JsonArray(f.chats.map { JsonPrimitive(it.toString()) })) }.toString(),true).get(5,TimeUnit.SECONDS)
            val reply=wire.next();assertEquals(request.toString(),reply.getValue("requestId").jsonPrimitive.content)
            assertEquals(f.chats.map(UUID::toString).toSet(),reply.getValue("acceptedChatIds").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
        private fun request(path:String,body:String?=null,key:UUID?=null,vote:Boolean=false):JsonObject {
            val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(20)).header("Authorization",f.bearer())
            if(key!=null)builder.header("Idempotency-Key",key.toString())
            if(body==null)builder.GET() else {
                builder.header("Content-Type","application/json")
                if(vote)builder.PUT(HttpRequest.BodyPublishers.ofString(body)) else builder.POST(HttpRequest.BodyPublishers.ofString(body))
            }
            val response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(if(key==null)200 else 201,response.statusCode(),"Synthetic native priority operation")
            return Json.parseToJsonElement(response.body()).jsonObject
        }
        fun get(path:String)=request(path)
        fun vote(id:UUID,going:Boolean)=request("/api/v1/proposals/$id/vote","""{"going":$going}""",vote=true)
        fun book(id:UUID,key:UUID,revision:Long)=request("/api/v1/proposals/$id/booking","""{"confirmOverlap":true,"expectedRevision":$revision}""",key)
        fun proposalEvent(id:UUID,revision:Long) {
            var found=false
            repeat(3) {
                if(!found) {
                    val event=wire.next();val type=event.getValue("type").jsonPrimitive.content
                    assertTrue(type in setOf("chat.message.created","proposal.updated"))
                    if(type=="proposal.updated") {
                        val payload=event.getValue("payload").jsonObject
                        assertEquals(id.toString(),payload.getValue("proposalId").jsonPrimitive.content)
                        assertEquals(revision,payload.getValue("revision").jsonPrimitive.long);assertFalse(payload.containsKey("viewerGoing"));found=true
                    }
                }
            }
            assertTrue(found)
        }
        fun bookingEvent(proposal:UUID,booking:UUID,status:String,reason:String?,revision:Long) {
            proposalEvent(proposal,revision)
            val event=wire.next();assertEquals("booking.changed",event.getValue("type").jsonPrimitive.content)
            assertEquals(buildJsonObject { put("proposalId",proposal.toString());put("bookingId",booking.toString());put("status",status);put("cancelReason",reason?.let(::JsonPrimitive)?:JsonNull) },event.getValue("payload"))
        }
        suspend fun silence() {
            val marker=UUID.randomUUID()
            hub.publishCommitted(RealtimeEvent(marker,RealtimeEventType.NOTIFICATION_CREATED,RealtimeScopeKind.USER,f.actor,Instant.now(),buildJsonObject { put("marker",marker.toString()) }))
            assertEquals(marker.toString(),wire.next().getValue("eventId").jsonPrimitive.content)
        }
        override fun close() { socket.abort();server.stop(0,1000) }
    }
    private class Wire:WebSocket.Listener {
        private val frames=LinkedBlockingQueue<JsonObject>();private val buffer=StringBuilder()
        override fun onOpen(socket:WebSocket) { socket.request(1) }
        override fun onText(socket:WebSocket,data:CharSequence,last:Boolean):CompletionStage<*> {
            buffer.append(data);if(last){frames.add(Json.parseToJsonElement(buffer.toString()).jsonObject);buffer.setLength(0)}
            socket.request(1);return CompletableFuture.completedFuture(null)
        }
        fun next():JsonObject=assertNotNull(frames.poll(5,TimeUnit.SECONDS),"Expected native WS priority frame")
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_booking_priority$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-booking-priority-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val service = BookingService(runtime)
        val actor = UUID.randomUUID()
        private val peer = UUID.randomUUID()
        private val points = List(2) { UUID.randomUUID() }
        val chats = List(3) { UUID.randomUUID() }
        private val start = Instant.parse("2030-01-01T12:00:00Z")
        private val bookedAt = Instant.parse("2026-01-01T00:00:00Z")

        private val session = UUID.randomUUID()
        fun bearer(): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun revision(id: UUID, value: Long) = connection().use { c -> execute(c, "UPDATE meeting_proposals SET revision=? WHERE id=?", value, id) }
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in listOf(actor, peer)) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Overlap query fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)", session, actor, session.toString().replace("-", "").repeat(2))
                    for ((index, point) in points.withIndex()) execute(c,
                        """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                            VALUES (?,ST_SetSRID(ST_MakePoint(?,55.75),4326),'Synthetic overlap fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""",
                        point, 37.62 + index, actor)
                    for ((index, chat) in chats.withIndex()) {
                        val topic = UUID.randomUUID()
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())",
                            topic, points[if (index == 2) 1 else 0], if (index == 1) "basketball" else "football")
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                        for (person in listOf(actor, peer)) execute(c,
                            "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, person)
                    }
                    c.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }

        private fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun proposal(chat: UUID, from: Long, to: Long): UUID = connection().use { c ->
            c.autoCommit = false
            val id = UUID.randomUUID()
            insertProposal(c, id, chat, from, to)
            c.commit()
            id
        }
        private fun insertProposal(c: Connection, id: UUID, chat: UUID, from: Long, to: Long) {
            execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                VALUES (?,?,?,?,?,2,?,?)""", id, chat, actor, time(from), time(to), bookedAt.atOffset(ZoneOffset.UTC), bookedAt.atOffset(ZoneOffset.UTC))
            for (person in listOf(actor, peer)) execute(c,
                "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,?)", id, person, bookedAt.atOffset(ZoneOffset.UTC))
        }
        fun seedBooking(chat: UUID, from: Long, to: Long, cancelled: Boolean = false, booking: UUID = UUID.randomUUID()): BookingConflictView = connection().use { c ->
            c.autoCommit = false
            val proposal = UUID.randomUUID()
            insertProposal(c, proposal, chat, from, to)
            c.prepareStatement("INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,?) RETURNING booking_order").use { s ->
                s.setObject(1, booking); s.setObject(2, proposal); s.setObject(3, actor); s.setObject(4, bookedAt.atOffset(ZoneOffset.UTC))
                s.executeQuery().use { r ->
                    check(r.next())
                    val order = r.getLong(1)
                    execute(c, "UPDATE meeting_proposals SET revision=3 WHERE id=?", proposal)
                    if (cancelled) execute(c, "UPDATE bookings SET status='CANCELLED',cancelled_at=?,cancel_reason='INSUFFICIENT_VOTES' WHERE id=?",
                        bookedAt.plusSeconds(3600).atOffset(ZoneOffset.UTC), booking)
                    c.commit()
                    BookingConflictView(booking, proposal, start.plusSeconds(from * 60), start.plusSeconds(to * 60), bookedAt, order)
                }
            }
        }
        private fun time(minutes: Long) = start.plusSeconds(minutes * 60).atOffset(ZoneOffset.UTC)
        fun deleteCandidate(id: UUID) = connection().use { c -> execute(c, "DELETE FROM meeting_proposals WHERE id=?", id) }

        fun snapshot(): List<List<List<String?>>> = connection().use { c ->
            val queries = listOf(
                "SELECT b.* FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id JOIN chats c ON c.id=p.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY b.id",
                "SELECT p.* FROM meeting_proposals p JOIN chats c ON c.id=p.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY p.id",
                "SELECT v.* FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id JOIN chats c ON c.id=p.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY v.proposal_id,v.user_id",
                "SELECT id,updated_at FROM points WHERE id IN (?,?) ORDER BY id",
                "SELECT c.id,c.last_message_seq FROM chats c JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY c.id",
                "SELECT m.id,m.message_seq FROM messages m JOIN chats c ON c.id=m.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY m.id",
                "SELECT id,event_type,dedupe_key FROM notification_events WHERE recipient_user_id IN (?,?) ORDER BY id",
                "SELECT actor_user_id,operation_type,idempotency_key,response_snapshot FROM operation_idempotency WHERE actor_user_id IN (?,?) ORDER BY actor_user_id,operation_type,idempotency_key",
            )
            queries.mapIndexed { index, query ->
                c.prepareStatement(query).use { s ->
                    val ids = if (index >= 6) listOf(actor, peer) else points
                    ids.forEachIndexed { n, id -> s.setObject(n + 1, id) }
                    s.executeQuery().use { r -> buildList {
                        while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
                    } }
                }
            } + listOf(sequence(c))
        }
        private fun sequence(c: Connection): List<List<String?>> {
            val name = c.createStatement().use { s -> s.executeQuery("SELECT pg_get_serial_sequence('bookings','booking_order')").use { r -> check(r.next()); r.getString(1) } }
            require(Regex("^[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*$").matches(name))
            return c.createStatement().use { s -> s.executeQuery("SELECT last_value,is_called FROM $name").use { r ->
                check(r.next()); listOf(listOf(r.getString(1), r.getString(2)))
            } }
        }
        override fun close() {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (point in points) execute(c, "DELETE FROM points WHERE id=?", point)
                    for (person in listOf(actor, peer)) execute(c, "DELETE FROM users WHERE id=?", person)
                    c.commit()
                }
            } finally { runtime.close() }
        }
    }
    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any?) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { n, value -> s.setObject(n + 1, value) }; s.executeUpdate() }
        }
    }
}
