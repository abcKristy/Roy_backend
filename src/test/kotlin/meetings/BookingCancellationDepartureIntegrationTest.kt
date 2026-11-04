package com.example.meetings

import com.example.accounts.AccountDeletionService
import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.*
import com.example.points.PointWriteService
import com.example.testing.testDatabaseUrl
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
import java.util.UUID
import kotlin.test.*

class BookingCancellationDepartureIntegrationTest {
    @Test
    fun `native leave rolls back at commit then settles all chat proposals while other topics preserve point`() {
        Fixture().use { f ->
            val initial = f.state()
            val unrelated = f.foreignState()
            val fragile = f.read(f.b, f.fragile)
            val stable = f.read(f.b, f.stable)
            assertEquals("booked", fragile.getValue("status").jsonPrimitive.content)
            assertEquals("booked", stable.getValue("status").jsonPrimitive.content)
            assertError(f.leave(null, f.chat, 401), "AUTH_REQUIRED")
            assertError(f.leave(f.a, UUID.randomUUID(), 404), "RESOURCE_GONE")
            assertEquals(initial, f.state())
            // A nonmember cannot remove another actor's membership or votes.
            f.leave(f.a, f.foreignChat, 204)
            assertEquals(initial, f.state())
            f.installFault()
            try {
                f.assertDeferredFlags()
                assertError(f.leave(f.a, f.chat, 500), "INTERNAL_ERROR")
                assertEquals(initial, f.state(), "COMMIT failure must restore memberships, votes, bookings, revision, SYSTEM/seq and notifications")
                assertEquals(fragile, f.read(f.b, f.fragile))
            } finally { f.dropFault() }
            f.leave(f.a, f.chat, 204)
            f.assertTransition(fragile, f.read(f.b, f.fragile), cancelled = true, anonymized = false, count = 1, revision = 3)
            f.assertTransition(stable, f.read(f.b, f.stable), cancelled = false, anonymized = false, count = 2, revision = 4)
            assertEquals(0, f.count("SELECT count(*) FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=? AND v.user_id=?", f.chat, f.a))
            assertEquals(1, f.count("SELECT count(*) FROM votes WHERE proposal_id=? AND user_id=?", f.siblingProposal, f.a))
            assertEquals(1, f.count("SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NOT NULL", f.chat, f.a))
            assertEquals(1, f.count("SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL", f.siblingChat, f.a))
            assertError(f.read(f.a, f.fragile, 403), "NOT_MEMBER")
            f.assertCancellation(f.fragile, 2)
            assertEquals(unrelated, f.foreignState())
            val settled = f.state()
            f.leave(f.a, f.chat, 204)
            assertEquals(settled, f.state())

            // With B then C leaving, zero votes must not re-cancel either historical booking.
            val cancelledHistory = f.read(f.b, f.fragile).getValue("booking")
            f.leave(f.b, f.chat, 204)
            assertEquals(cancelledHistory, f.read(f.c, f.fragile).getValue("booking"))
            f.assertCancellation(f.fragile, 2)
            f.assertCancellation(f.stable, 1)
            val beforeZero = f.bookingState()
            f.leave(f.c, f.chat, 204)
            assertEquals(beforeZero, f.bookingState(), "Zero remaining votes must preserve both cancellation timestamps and full booking history")
            assertEquals(4, f.count("SELECT revision FROM meeting_proposals WHERE id=?", f.fragile))
            assertEquals(6, f.count("SELECT revision FROM meeting_proposals WHERE id=?", f.stable))
            assertEquals(0, f.count("SELECT count(*) FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=?", f.chat))
            assertEquals(2, f.count("SELECT count(*) FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id=? AND b.status='CANCELLED'", f.chat))
            assertEquals(1, f.count("SELECT count(*) FROM points WHERE id=?", f.point), "Sibling topic member preserves point even when target chat has no members")
            assertEquals(2, f.count("SELECT count(*) FROM messages WHERE chat_id=? AND system_type='meeting_booking_cancelled'", f.chat))
            val zero = f.state()
            f.leave(f.c, f.chat, 204)
            assertEquals(zero, f.state())
            f.leave(f.a, f.siblingChat, 204)
            assertEquals(0, f.count("SELECT count(*) FROM points WHERE id=?", f.point))
            assertEquals(0, f.count("SELECT count(*) FROM chats WHERE id=ANY(?)", listOf(f.chat, f.siblingChat)))
            assertError(f.leave(f.a, f.siblingChat, 404), "RESOURCE_GONE")
            assertEquals(1, f.count("SELECT count(*) FROM points WHERE id=?", f.lonelyPoint))
            f.leave(f.a, f.lonelyChat, 204)
            assertEquals(0, f.count("SELECT count(*) FROM points WHERE id=?", f.lonelyPoint))
            assertEquals(unrelated, f.foreignState())
        }
    }

    @Test
    fun `native account deletion atomically cancels poststart minority and preserves anonymized common graph`() {
        Fixture().use { f ->
            val initial = f.state()
            val unrelated = f.foreignState()
            val fragile = f.read(f.b, f.fragile)
            val stable = f.read(f.b, f.stable)
            assertError(f.delete(null, expected = 401), "AUTH_REQUIRED")
            assertError(f.delete(f.a, password = "wrong-synthetic-password", expected = 422), "INVALID_CURRENT_PASSWORD")
            assertError(f.delete(f.a, confirm = false, expected = 422), "INVALID_FIELD")
            assertEquals(initial, f.state())
            f.installFault()
            try {
                f.assertDeferredFlags()
                assertError(f.delete(f.a, expected = 500), "INTERNAL_ERROR")
                assertEquals(initial, f.state(), "COMMIT failure restores all memberships, votes, shared history, private state and both active sessions")
                assertEquals(fragile, f.read(f.a, f.fragile))
                assertEquals(fragile, f.read(f.a, f.fragile, alternateSession = true))
            } finally { f.dropFault() }
            f.delete(f.a, expected = 204)
            f.assertTransition(fragile, f.read(f.b, f.fragile), cancelled = true, anonymized = true, count = 1, revision = 3)
            f.assertTransition(stable, f.read(f.b, f.stable), cancelled = false, anonymized = true, count = 2, revision = 4)
            assertEquals(0, f.count("SELECT count(*) FROM votes WHERE user_id=?", f.a))
            assertEquals(0, f.count("SELECT count(*) FROM chat_memberships WHERE user_id=?", f.a))
            assertEquals(2, f.count("SELECT count(*) FROM auth_sessions WHERE user_id=? AND revoked_at IS NOT NULL", f.a))
            assertEquals(1, f.count("SELECT count(*) FROM users WHERE id=? AND deleted_at IS NOT NULL AND phone_e164 IS NULL AND password_hash IS NULL AND display_name IS NULL AND age_years IS NULL AND auth_version=1", f.a))
            assertEquals(0, f.count("SELECT count(*) FROM notification_settings WHERE user_id=?", f.a))
            assertEquals(0, f.count("SELECT count(*) FROM notification_events WHERE recipient_user_id=?", f.a))
            assertEquals(1, f.count("SELECT count(*) FROM points WHERE id=? AND created_by_user_id IS NULL", f.point))
            assertEquals(2, f.count("SELECT count(*) FROM chats c JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id=?", f.point))
            assertEquals(0, f.count("SELECT count(*) FROM points WHERE id=?", f.lonelyPoint))
            assertEquals(1, f.count("SELECT count(*) FROM messages WHERE id=? AND author_user_id IS NULL AND body='Synthetic shared history'", f.textMessage))
            assertEquals(0, f.count("SELECT count(*) FROM messages WHERE chat_id=ANY(?) AND (author_user_id=? OR system_payload->>'actorUserId'=?)", listOf(f.chat, f.siblingChat), f.a, f.a.toString()))
            assertEquals(0, f.count("SELECT count(*) FROM meeting_proposals WHERE proposer_user_id=?", f.a))
            assertEquals(0, f.count("SELECT count(*) FROM bookings WHERE booked_by_user_id=?", f.a))
            f.assertCancellation(f.fragile, 2)
            assertEquals(unrelated, f.foreignState())
            assertError(f.read(f.a, f.fragile, 401), "SESSION_EXPIRED")
            assertError(f.read(f.a, f.fragile, 401, alternateSession = true), "SESSION_EXPIRED")
            val settled = f.state()
            assertError(f.delete(f.a, expected = 401), "SESSION_EXPIRED")
            assertEquals(settled, f.state())
        }
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_cancel_leave$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        private val secret = "m4-cancellation-departure-secret-at-least-32-bytes"
        private val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        private val people = listOf(a, b, c)
        private val sessions = people.associateWith { UUID.randomUUID() }
        private val alternate = UUID.randomUUID()
        val point = UUID.randomUUID(); private val foreignPoint = UUID.randomUUID(); val lonelyPoint = UUID.randomUUID()
        private val points = listOf(point, foreignPoint, lonelyPoint)
        val chat = UUID.randomUUID(); val siblingChat = UUID.randomUUID(); val foreignChat = UUID.randomUUID(); val lonelyChat = UUID.randomUUID()
        private val chats = listOf(chat, siblingChat, foreignChat, lonelyChat)
        val fragile = UUID.randomUUID(); val stable = UUID.randomUUID(); val siblingProposal = UUID.randomUUID()
        private val foreignProposal = UUID.randomUUID(); private val lonelyProposal = UUID.randomUUID()
        val textMessage = UUID.randomUUID()
        private val fault = "m4_departure_commit_" + a.toString().replace("-", "")
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val hasher = Argon2idPasswordHasher()
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            configureTransport()
            configureRouting(runtime, sessionAccess = SessionAccessService(secret, JdbcSessionRepository(runtime)),
                proposalService = ProposalService(runtime, secret), bookingService = BookingService(runtime),
                pointWriteService = PointWriteService(runtime), accountDeletionService = AccountDeletionService(runtime, PasswordHashingWork(hasher)))
        }
        private var port = 0
        init {
            try {
                connection().use { conn ->
                    conn.autoCommit = false
                    val hash = hasher.hash(PASSWORD)
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(conn, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,?,'Departure fixture',20,'test',clock_timestamp(),clock_timestamp()-interval '1 day',clock_timestamp())""", id, phone, hash)
                        execute(conn, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                        seedSession(conn, sessions.getValue(id), id)
                    }
                    seedSession(conn, alternate, a)
                    for (id in points) execute(conn, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic departure point','MANUAL',?,clock_timestamp()-interval '1 day',clock_timestamp())""", id, if (id == foreignPoint) b else a)
                    for ((index, id) in chats.withIndex()) {
                        val parent = when (id) { chat, siblingChat -> point; foreignChat -> foreignPoint; else -> lonelyPoint }
                        val topic = UUID.randomUUID()
                        execute(conn, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())", topic, parent, if (index == 1) "basketball" else "football")
                        execute(conn, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", id, topic)
                        val members = when (id) { chat -> people; foreignChat -> listOf(b, c); else -> listOf(a) }
                        for (actor in members) execute(conn, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp()-interval '1 day')", id, actor)
                    }
                    for (id in listOf(fragile, stable, siblingProposal, foreignProposal, lonelyProposal)) {
                        val target = when (id) { fragile, stable -> chat; siblingProposal -> siblingChat; foreignProposal -> foreignChat; else -> lonelyChat }
                        val voters = when (id) { fragile -> listOf(a,b); stable -> people; foreignProposal -> listOf(b,c); else -> listOf(a) }
                        execute(conn, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                            VALUES (?,?,?,clock_timestamp()-interval '2 hours',clock_timestamp()-interval '1 hour',?,clock_timestamp()-interval '5 hours',clock_timestamp()-interval '3 hours')""", id, target, if (target == siblingChat || target == lonelyChat) a else b, voters.size.toLong())
                        for (actor in voters) execute(conn, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp()-interval '4 hours')", id, actor)
                        if (id in listOf(fragile,stable,foreignProposal)) execute(conn, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp()-interval '3 hours')", UUID.randomUUID(), id, if (id == foreignProposal) b else a)
                    }
                    execute(conn, "UPDATE chats SET last_message_seq=1 WHERE id=?", chat)
                    execute(conn, "INSERT INTO messages(id,chat_id,message_seq,author_user_id,type,body,created_at) VALUES (?,?,1,?,'TEXT','Synthetic shared history',clock_timestamp()-interval '3 hours')", textMessage, chat, a)
                    JdbcSystemMessageWriter().append(conn, UUID.randomUUID(), SystemMessageDraft(SystemMessageKind.MEMBER_JOINED, a, point, chat))
                    conn.commit()
                }
                server.start(wait = false)
                port = runBlocking { server.engine.resolvedConnectors().single().port }
            } catch (failure: Throwable) { server.stop(0, 1000); runtime.close(); throw failure }
        }
        fun connection(): Connection = DriverManager.getConnection(url,user,password)
        private fun seedSession(conn: Connection, id: UUID, actor: UUID) = execute(conn,
            "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)", id, actor, id.toString().replace("-", "").repeat(2))
        private fun request(actor: UUID?, path: String, method: String, body: String?, expected: Int, alternateSession: Boolean = false): JsonObject {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(20))
            if (actor != null) {
                val now = Instant.now()
                builder.header("Authorization", "Bearer " + JwtTokenIssuer(secret).issue(actor, if (alternateSession) alternate else sessions.getValue(actor), 0, now.minusSeconds(1),now.plusSeconds(3600)))
            }
            if (body != null) builder.header("Content-Type","application/json")
            val response = client.send(builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(expected,response.statusCode(),"Native departure $method expected status")
            if (expected == 204) { assertTrue(response.body().isEmpty()); return buildJsonObject {} }
            return Json.parseToJsonElement(response.body()).jsonObject
        }
        fun leave(actor: UUID?, target: UUID, expected: Int) = request(actor,"/api/v1/chats/$target/membership","DELETE",null,expected)
        fun delete(actor: UUID?, password: String = PASSWORD, confirm: Boolean = true, expected: Int) = request(actor,"/api/v1/me/deletion","POST",buildJsonObject { put("currentPassword",password);put("confirm",confirm) }.toString(),expected)
        fun read(actor: UUID, proposal: UUID, expected: Int = 200, alternateSession: Boolean = false) = request(actor,"/api/v1/proposals/$proposal","GET",null,expected,alternateSession)
        fun count(sql: String, vararg values: Any): Int = connection().use { conn ->
            conn.prepareStatement(sql).use { s ->
                val arrays = bind(conn,s,values)
                try { s.executeQuery().use { r -> check(r.next()); r.getInt(1) } } finally { arrays.forEach { it.free() } }
            }
        }
        fun state(): Map<String,List<List<String?>>> = connection().use { conn ->
            val clauses = linkedMapOf(
                "points" to "SELECT * FROM points WHERE id=ANY(?) ORDER BY id",
                "topics" to "SELECT * FROM point_topics WHERE point_id=ANY(?) ORDER BY id",
                "memberships" to "SELECT * FROM chat_memberships WHERE chat_id=ANY(?) ORDER BY chat_id,user_id",
                "chats" to "SELECT * FROM chats WHERE id=ANY(?) ORDER BY id",
                "proposals" to "SELECT * FROM meeting_proposals WHERE chat_id=ANY(?) ORDER BY id",
                "votes" to "SELECT v.* FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=ANY(?) ORDER BY v.proposal_id,v.user_id",
                "bookings" to "SELECT b.* FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id=ANY(?) ORDER BY b.id",
                "messages" to "SELECT * FROM messages WHERE chat_id=ANY(?) ORDER BY id",
                "notifications" to "SELECT * FROM notification_events WHERE chat_id=ANY(?) ORDER BY id",
                "jobs" to "SELECT j.* FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.chat_id=ANY(?) ORDER BY j.id",
                "users" to "SELECT id,auth_version,deleted_at,updated_at,phone_e164 IS NULL,password_hash IS NULL,display_name IS NULL,age_years IS NULL FROM users WHERE id=ANY(?) ORDER BY id",
                "sessions" to "SELECT id,user_id,auth_version,revoked_at,expires_at FROM auth_sessions WHERE user_id=ANY(?) ORDER BY id",
                "settings" to "SELECT * FROM notification_settings WHERE user_id=ANY(?) ORDER BY user_id")
            clauses.mapValues { (label,sql) -> rows(conn,sql, when(label) { "points","topics" -> points; "users","sessions","settings" -> people; else -> chats }) }
        }
        fun bookingState(): List<List<String?>> = connection().use { conn -> rows(conn,
            "SELECT b.* FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id=ANY(?) ORDER BY b.id", listOf(chat)) }
        fun foreignState(): List<List<String?>> = connection().use { conn -> rows(conn,
            """SELECT b.id,b.booking_order,b.booked_by_user_id,b.status,b.created_at,b.cancelled_at,b.cancel_reason,p.revision,p.updated_at,
                (SELECT count(*) FROM votes WHERE proposal_id=p.id),c.last_message_seq,t.point_id,pt.updated_at
                FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id JOIN chats c ON c.id=p.chat_id
                JOIN point_topics t ON t.id=c.point_topic_id JOIN points pt ON pt.id=t.point_id WHERE p.chat_id=ANY(?)""",listOf(foreignChat)) }
        private fun rows(conn: Connection,sql: String,ids: List<UUID>): List<List<String?>> = conn.prepareStatement(sql).use { s ->
            val array = conn.createArrayOf("uuid",ids.toTypedArray())
            try { s.setArray(1,array);s.executeQuery().use { r -> buildList { while(r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } } } finally { array.free() }
        }
        fun installFault() = connection().use { conn -> conn.createStatement().use { s ->
            s.execute("CREATE FUNCTION $fault() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic deferred departure COMMIT fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE CONSTRAINT TRIGGER $fault AFTER UPDATE ON bookings DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.proposal_id='$fragile'::uuid AND NEW.status='CANCELLED') EXECUTE FUNCTION $fault()")
        } }
        fun assertDeferredFlags() = connection().use { conn -> conn.prepareStatement("SELECT tgdeferrable,tginitdeferred FROM pg_trigger WHERE tgname=? AND tgrelid='bookings'::regclass").use { s ->
            s.setString(1,fault);s.executeQuery().use { r -> assertTrue(r.next());assertTrue(r.getBoolean(1));assertTrue(r.getBoolean(2));assertFalse(r.next()) }
        } }
        fun dropFault() = connection().use { conn -> conn.createStatement().use { s -> s.execute("DROP TRIGGER IF EXISTS $fault ON bookings");s.execute("DROP FUNCTION IF EXISTS $fault()") } }
        fun assertTransition(before: JsonObject,after: JsonObject,cancelled: Boolean,anonymized: Boolean,count: Int,revision: Long) {
            assertEquals(count,after.getValue("goingCount").jsonPrimitive.int)
            assertEquals(revision,after.getValue("revision").jsonPrimitive.long)
            assertEquals(if(cancelled) "closed" else "booked",after.getValue("status").jsonPrimitive.content)
            val previous = before.getValue("booking").jsonObject
            val booking = after.getValue("booking").jsonObject
            for(field in listOf("id","proposalId","bookingOrder","bookedAt")) assertEquals(previous.getValue(field),booking.getValue(field))
            assertEquals(if(anonymized) JsonNull else previous.getValue("bookedByUserId"),booking.getValue("bookedByUserId"))
            assertEquals(if(cancelled) "cancelled" else "active",booking.getValue("status").jsonPrimitive.content)
            if(cancelled) {
                Instant.parse(booking.getValue("cancelledAt").jsonPrimitive.content)
                assertEquals("insufficient_votes",booking.getValue("cancelReason").jsonPrimitive.content)
            } else { assertEquals(JsonNull,booking.getValue("cancelledAt"));assertEquals(JsonNull,booking.getValue("cancelReason")) }
        }
        fun assertCancellation(proposal: UUID,recipients: Int) {
            assertEquals(1,count("SELECT count(*) FROM messages WHERE chat_id=? AND system_type='meeting_booking_cancelled' AND system_payload->>'proposalId'=?",chat,proposal.toString()))
            assertEquals(recipients,count("SELECT count(*) FROM notification_events WHERE proposal_id=? AND event_type='booking_cancelled'",proposal))
        }
        override fun close() {
            try {
                server.stop(0,1000);dropFault()
                connection().use { conn ->
                    conn.autoCommit=false
                    for(id in points) execute(conn,"DELETE FROM points WHERE id=?",id)
                    for(id in people) execute(conn,"DELETE FROM users WHERE id=?",id)
                    conn.commit()
                }
            } finally { runtime.close() }
        }
        companion object { const val PASSWORD = "synthetic-departure-password" }
    }
    companion object {
        private fun assertError(body: JsonObject,code: String) { assertEquals(code,body.getValue("error").jsonObject.getValue("code").jsonPrimitive.content) }
        private fun bind(conn: Connection,s: java.sql.PreparedStatement,values: Array<out Any>): List<java.sql.Array> = buildList {
            values.forEachIndexed { index,value ->
                if(value is List<*>) { val array=conn.createArrayOf("uuid",value.toTypedArray());add(array);s.setArray(index+1,array) } else s.setObject(index+1,value)
            }
        }
        private fun execute(conn: Connection,sql: String,vararg values: Any) { conn.prepareStatement(sql).use { s -> values.forEachIndexed { i,v ->s.setObject(i+1,v) };s.executeUpdate() } }
    }
}
