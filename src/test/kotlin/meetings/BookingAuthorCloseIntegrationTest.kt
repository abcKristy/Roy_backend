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
import java.time.Clock
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

class BookingAuthorCloseIntegrationTest {
    @Test
    fun `close revision must be a native JSON Long without effects`() = runBlocking<Unit> {
        Fixture().use { f ->
            val b=f.seedBooking(f.chats[0],0,60)
            Http(f).use { http ->
                for(value in listOf("\"3\"","\"3.0\"","null","true","{}","[]","3.0","3.5","9223372036854775808","-1","-9223372036854775808",null)) {
                    val before=f.snapshot();code(http.close(b.proposalId,UUID.randomUUID(),value,422),"INVALID_FIELD");assertEquals(before,f.snapshot());http.silence()
                }
                val before=f.snapshot();code(http.close(b.proposalId,UUID.randomUUID(),Long.MAX_VALUE.toString(),409),"STALE_STATE");assertEquals(before,f.snapshot());http.silence()
            }
        }
    }
    @Test
    fun `native close requires active author not booking owner and current revision`() = runBlocking<Unit> {
        Fixture().use { f ->
            val b=f.seedBooking(f.chats[0],0,60)
            val closed=f.proposal(f.chats[0],90,120);f.markClosed(closed)
            Http(f).use { http ->
                suspend fun reject(id:UUID,revision:String,expected:Int,error:String,actor:UUID?=f.actor) {
                    val before=f.snapshot();code(http.close(id,UUID.randomUUID(),revision,expected,actor),error);assertEquals(before,f.snapshot());http.silence()
                }
                reject(b.proposalId,"3",403,"FORBIDDEN",f.peer) // bookedBy is not proposer.
                reject(b.proposalId,"3",401,"AUTH_REQUIRED",null)
                reject(b.proposalId,"2",409,"STALE_STATE")
                reject(closed,"2",409,"PROPOSAL_CLOSED")
                reject(UUID.randomUUID(),"3",404,"RESOURCE_GONE")
                f.member(false);reject(b.proposalId,"3",403,"NOT_MEMBER");f.member(true)
                val result=http.close(b.proposalId,UUID.randomUUID(),"3",200)
                assertEquals("closed",result.getValue("status").jsonPrimitive.content)
                assertEquals(f.peer.toString(),result.getValue("booking").jsonObject.getValue("bookedByUserId").jsonPrimitive.content)
                http.closedEvents(b,4)
            }
        }
    }
    @Test
    fun `persisted UTC microsecond before exact and after startsAt boundaries are deterministic`() = runBlocking<Unit> {
        for(booked in listOf(false,true)) for(offset in listOf(-1000L,0L,1000L)) Fixture().use { f ->
            val b=if(booked) f.seedBooking(f.chats[0],0,60) else null
            val id=b?.proposalId?:f.proposal(f.chats[0],0,60)
            val start=f.startsAt(id);assertEquals(0,start.nano%1000)
            val now=start.plusNanos(offset)
            Http(f,Clock.fixed(now,ZoneOffset.UTC)).use { http ->
                val before=f.snapshot();val result=http.close(id,UUID.randomUUID(),if(booked)"3" else "2",if(offset<0)200 else 409)
                if(offset>=0) {code(result,"MEETING_ALREADY_STARTED");assertEquals(before,f.snapshot());http.silence()}
                else {
                    assertEquals("closed",result.getValue("status").jsonPrimitive.content)
                    assertEquals(start.toString(),result.getValue("startsAt").jsonPrimitive.content)
                    assertEquals(now.toString(),result.getValue("updatedAt").jsonPrimitive.content)
                    if(booked) {assertEquals(now.toString(),result.getValue("booking").jsonObject.getValue("cancelledAt").jsonPrimitive.content);http.closedEvents(b!!,4)}
                    else {assertEquals(JsonNull,result.getValue("booking"));http.unbookedClosedEvents(id,3)}
                }
            }
        }
    }
    @Test
    fun `deferred close commit rolls back jobs and events then retry atomically closes and replay preserves newer state`() = runBlocking<Unit> {
        Fixture().use { f ->
            val b=f.seedBooking(f.chats[0],0,60);val other=f.proposal(f.chats[0],90,120);f.seedJobs(b)
            Http(f).use { http ->
                val key=UUID.randomUUID();val before=f.snapshot();val detail=http.get(b.proposalId)
                f.installFault()
                try {code(http.close(b.proposalId,key,"3",500),"INTERNAL_ERROR");assertEquals(before,f.snapshot());assertEquals(detail,http.get(b.proposalId));http.silence()} finally {f.dropFault()}
                val result=http.close(b.proposalId,key,"3",200)
                assertEquals("closed",result.getValue("status").jsonPrimitive.content)
                assertEquals("closed_by_author",result.getValue("booking").jsonObject.getValue("cancelReason").jsonPrimitive.content)
                f.assertEffects(b,key,result);http.closedEvents(b,4)
                assertEquals(result,http.get(b.proposalId))
                f.revision(b.proposalId,5)
                val newer=http.get(b.proposalId);assertEquals(5L,newer.getValue("revision").jsonPrimitive.long)
                val committed=f.snapshot()
                assertEquals(result,http.close(b.proposalId,key,"3",200));assertEquals(committed,f.snapshot());assertEquals(newer,http.get(b.proposalId));http.silence()
                code(http.close(other,key,"2",409),"IDEMPOTENCY_KEY_REUSED");assertEquals(committed,f.snapshot());http.silence()
            }
        }
    }
    private fun code(value:JsonObject,expected:String)=assertEquals(expected,value.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    private class Http(private val f:Fixture,clock:Clock?=null):AutoCloseable {
        private val access=SessionAccessService(f.secret,JdbcSessionRepository(f.runtime));private val hub=RealtimeHub(f.runtime,access)
        private val server=embeddedServer(Netty,host="127.0.0.1",port=0) {
            configureTransport();installRealtimeTransport()
            configureRouting(f.runtime,sessionAccess=access,realtimeHub=hub,
                proposalService=ProposalService(f.runtime,f.secret),bookingService=BookingService(f.runtime,closeClock=clock))
        }
        private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();private val port:Int
        private val wire=Wire();private val socket:WebSocket
        init {
            server.start(wait=false);port=runBlocking {server.engine.resolvedConnectors().single().port}
            socket=client.newWebSocketBuilder().header("Authorization",f.bearer()).buildAsync(URI("ws://127.0.0.1:$port/api/v1/ws"),wire).get(5,TimeUnit.SECONDS)
            assertEquals("hello",wire.next().getValue("type").jsonPrimitive.content)
            val key=UUID.randomUUID();socket.sendText(buildJsonObject {put("v",1);put("type","subscribe");put("requestId",key.toString());put("chatIds",JsonArray(f.chats.map {JsonPrimitive(it.toString())}))}.toString(),true).get(5,TimeUnit.SECONDS)
            assertEquals(key.toString(),wire.next().getValue("requestId").jsonPrimitive.content)
        }
        fun close(id:UUID,key:UUID,revision:String?,expected:Int,actor:UUID?=f.actor):JsonObject {
            val body=if(revision==null)"{}" else """{"expectedRevision":$revision}"""
            return request(id,body,key,expected,actor)
        }
        fun get(id:UUID)=request(id,null,null,200,f.actor)
        private fun request(id:UUID,body:String?,key:UUID?,expected:Int,actor:UUID?):JsonObject {
            val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/$id${if(body==null)"" else "/close"}"))
                .timeout(Duration.ofSeconds(20))
            if(actor!=null)builder.header("Authorization",f.bearer(actor))
            val request=if(body==null)builder.GET().build() else builder.header("Content-Type","application/json").header("Idempotency-Key",key.toString()).POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),"Synthetic close DTO=$body")
            return Json.parseToJsonElement(response.body()).jsonObject
        }
        fun closedEvents(b:BookingConflictView,revision:Long) {
            val frames=List(4){wire.next()}
            assertEquals(listOf("chat.message.created","chat.message.created","proposal.updated","booking.changed"),frames.map {it.getValue("type").jsonPrimitive.content})
            assertEquals(listOf("meeting_booking_cancelled","meeting_closed"),frames.take(2).map {it.getValue("payload").jsonObject.getValue("system").jsonObject.getValue("kind").jsonPrimitive.content})
            assertEquals(revision,frames[2].getValue("payload").jsonObject.getValue("revision").jsonPrimitive.long)
            assertEquals(buildJsonObject {put("proposalId",b.proposalId.toString());put("bookingId",b.bookingId.toString());put("status","cancelled");put("cancelReason","closed_by_author")},frames[3].getValue("payload"))
            assertTrue(frames.all {it.getValue("scope").jsonObject.getValue("kind").jsonPrimitive.content=="chat"});assertTrue(frames.none {it.toString().contains("viewerGoing")})
        }
        fun unbookedClosedEvents(id:UUID,revision:Long) {
            val message=wire.next();assertEquals("chat.message.created",message.getValue("type").jsonPrimitive.content)
            val update=wire.next();assertEquals("proposal.updated",update.getValue("type").jsonPrimitive.content)
            assertEquals(id.toString(),update.getValue("payload").jsonObject.getValue("proposalId").jsonPrimitive.content);assertEquals(revision,update.getValue("payload").jsonObject.getValue("revision").jsonPrimitive.long)
        }
        suspend fun silence() {val marker=UUID.randomUUID();hub.publishCommitted(RealtimeEvent(marker,RealtimeEventType.NOTIFICATION_CREATED,RealtimeScopeKind.USER,f.actor,Instant.now(),buildJsonObject {put("marker",marker.toString())}));assertEquals(marker.toString(),wire.next().getValue("eventId").jsonPrimitive.content)}
        override fun close() {socket.abort();server.stop(0,1000)}
    }
    private class Wire:WebSocket.Listener {
        private val queue=LinkedBlockingQueue<JsonObject>();private val buffer=StringBuilder()
        override fun onOpen(socket:WebSocket){socket.request(1)}
        override fun onText(socket:WebSocket,data:CharSequence,last:Boolean):CompletionStage<*> {buffer.append(data);if(last){queue.add(Json.parseToJsonElement(buffer.toString()).jsonObject);buffer.setLength(0)};socket.request(1);return CompletableFuture.completedFuture(null)}
        fun next():JsonObject=assertNotNull(queue.poll(5,TimeUnit.SECONDS),"Expected native close WS frame")
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_booking_close$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-overlap-query-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password,
            secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val service = BookingService(runtime)
        val actor = UUID.randomUUID()
        val peer = UUID.randomUUID()
        private val points = List(2) { UUID.randomUUID() }
        val chats = List(3) { UUID.randomUUID() }
        private val start = Instant.parse("2030-01-01T12:00:00Z")
        private val bookedAt = Instant.parse("2026-01-01T00:00:00Z")

        private val sessions = listOf(actor,peer).associateWith { UUID.randomUUID() }
        fun bearer(person:UUID = actor): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(person, sessions.getValue(person), 0, now.minusSeconds(1), now.plusSeconds(3600))
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
                    for ((person,session) in sessions) execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)", session, person, session.toString().replace("-", "").repeat(2))
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

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
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
        fun seedBooking(chat: UUID, from: Long, to: Long, cancelled: Boolean = false): BookingConflictView = connection().use { c ->
            c.autoCommit = false
            val proposal = UUID.randomUUID(); val booking = UUID.randomUUID()
            insertProposal(c, proposal, chat, from, to)
            c.prepareStatement("INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,?) RETURNING booking_order").use { s ->
                s.setObject(1, booking); s.setObject(2, proposal); s.setObject(3, peer); s.setObject(4, bookedAt.atOffset(ZoneOffset.UTC))
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
                "SELECT m.* FROM messages m JOIN chats c ON c.id=m.chat_id JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id IN (?,?) ORDER BY m.id",
                "SELECT * FROM notification_events WHERE recipient_user_id IN (?,?) ORDER BY id",
                "SELECT actor_user_id,operation_type,idempotency_key,response_snapshot FROM operation_idempotency WHERE actor_user_id IN (?,?) ORDER BY actor_user_id,operation_type,idempotency_key",
                "SELECT j.* FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.recipient_user_id IN (?,?) ORDER BY j.id",
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
        fun startsAt(id:UUID):Instant=connection().use { c -> c.prepareStatement("SELECT starts_at FROM meeting_proposals WHERE id=?").use { s -> s.setObject(1,id);s.executeQuery().use { r -> check(r.next());r.getObject(1,java.time.OffsetDateTime::class.java).toInstant() } } }
        fun member(active:Boolean)=connection().use { c -> execute(c,"UPDATE chat_memberships SET left_at=? WHERE chat_id=? AND user_id=?",if(active)null else Instant.now().atOffset(ZoneOffset.UTC),chats[0],actor) }
        fun markClosed(id:UUID)=connection().use { c -> execute(c,"UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='TIME' WHERE id=?",id) }
        fun seedJobs(booking:BookingConflictView)=connection().use { c ->
            c.autoCommit=false
            val installation=UUID.randomUUID()
            execute(c,"INSERT INTO device_registrations(installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at) VALUES (?,?,'IOS_APNS','synthetic-never-delivered-ciphertext',?,clock_timestamp(),clock_timestamp())",installation,peer,installation.toString().replace("-","").repeat(2))
            for(status in listOf("PENDING","PROCESSING","SENT")) {
                val event=UUID.randomUUID();val job=UUID.randomUUID()
                execute(c,"INSERT INTO notification_events(id,recipient_user_id,channel,event_type,dedupe_key,point_id,chat_id,proposal_id,booking_id,display_snapshot,visible_in_app,push_requested,created_at) VALUES (?,?,'REMINDERS','reminder_1h',?,?,?, ?,?,'{}'::jsonb,true,true,clock_timestamp())",event,peer,"close-fixture:$job",points[0],chats[0],booking.proposalId,booking.bookingId)
                execute(c,"INSERT INTO push_delivery_jobs(id,notification_event_id,installation_id,status,next_attempt_at,claim_token,lease_until,sent_at,completed_at) VALUES (?,?,?,?,clock_timestamp()+interval '1 hour',?,?,?,?)",job,event,installation,status,
                    if(status=="PROCESSING")UUID.randomUUID() else null,if(status=="PROCESSING")Instant.now().plusSeconds(120).atOffset(ZoneOffset.UTC) else null,
                    if(status=="SENT")bookedAt.atOffset(ZoneOffset.UTC) else null,if(status=="SENT")bookedAt.atOffset(ZoneOffset.UTC) else null)
            }
            c.commit()
        }
        private val fault="close_commit_"+UUID.randomUUID().toString().replace("-","")
        fun installFault()=connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE FUNCTION $fault() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic close deferred COMMIT fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE CONSTRAINT TRIGGER $fault AFTER INSERT ON operation_idempotency DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.actor_user_id='$actor'::uuid AND NEW.operation_type='close_meeting') EXECUTE FUNCTION $fault()")
            c.prepareStatement("SELECT tgdeferrable,tginitdeferred FROM pg_trigger WHERE tgname=?").use { q ->q.setString(1,fault);q.executeQuery().use { r ->check(r.next());assertTrue(r.getBoolean(1));assertTrue(r.getBoolean(2)) } }
        } }
        fun dropFault()=connection().use { c ->c.createStatement().use { s ->s.execute("DROP TRIGGER IF EXISTS $fault ON operation_idempotency");s.execute("DROP FUNCTION IF EXISTS $fault()") } }
        fun assertEffects(booking:BookingConflictView,key:UUID,body:JsonObject)=connection().use { c ->
            c.prepareStatement("SELECT p.lifecycle,p.closed_reason,p.revision,b.status,b.cancel_reason,(SELECT count(*) FROM votes WHERE proposal_id=p.id),(SELECT count(*) FROM messages WHERE chat_id=p.chat_id AND system_type IN ('meeting_booking_cancelled','meeting_closed')),(SELECT count(*) FROM notification_events WHERE booking_id=b.id AND event_type='booking_cancelled') FROM meeting_proposals p JOIN bookings b ON b.proposal_id=p.id WHERE b.id=?").use { s ->
                s.setObject(1,booking.bookingId);s.executeQuery().use { r ->check(r.next());assertEquals("CLOSED",r.getString(1));assertEquals("AUTHOR",r.getString(2));assertEquals(4,r.getInt(3));assertEquals("CANCELLED",r.getString(4));assertEquals("CLOSED_BY_AUTHOR",r.getString(5));assertEquals(2,r.getInt(6));assertEquals(2,r.getInt(7));assertEquals(1,r.getInt(8)) }
            }
            c.prepareStatement("SELECT j.status,j.claim_token,j.lease_until,j.sent_at,j.completed_at FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.booking_id=? AND e.event_type='reminder_1h' ORDER BY j.status").use { s ->s.setObject(1,booking.bookingId);s.executeQuery().use { r ->
                repeat(2){assertTrue(r.next());assertEquals("CANCELLED",r.getString(1));assertNull(r.getObject(2));assertNull(r.getObject(3));assertNull(r.getObject(4));assertNotNull(r.getObject(5))}
                assertTrue(r.next());assertEquals("SENT",r.getString(1));assertEquals(bookedAt,r.getObject(4,java.time.OffsetDateTime::class.java).toInstant());assertEquals(bookedAt,r.getObject(5,java.time.OffsetDateTime::class.java).toInstant());assertFalse(r.next())
            } }
            c.prepareStatement("SELECT outcome,result_ref,response_snapshot FROM operation_idempotency WHERE actor_user_id=? AND operation_type='close_meeting' AND idempotency_key=?").use { s ->s.setObject(1,actor);s.setString(2,key.toString());s.executeQuery().use { r ->assertTrue(r.next());assertEquals("SUCCESS",r.getString(1));assertEquals(booking.proposalId,r.getObject(2,UUID::class.java));assertEquals(buildJsonObject { put("status",200);put("body",body) },Json.parseToJsonElement(r.getString(3)));assertFalse(r.next()) } }
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
