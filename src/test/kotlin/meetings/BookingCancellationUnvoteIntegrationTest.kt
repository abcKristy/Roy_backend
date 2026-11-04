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

class BookingCancellationUnvoteIntegrationTest {
    @Test
    fun `native HTTP unvote cancellation commits atomically and desired repeats read current canonical state`() {
        Fixture().use { f ->
            val initial = f.state()
            val booked = f.read(f.actor, f.open)
            assertEquals("booked", booked["status"]!!.jsonPrimitive.content)
            assertEquals(2, booked["goingCount"]!!.jsonPrimitive.int)
            assertError(f.vote(null, f.open, """{"going":false}""", 401), "AUTH_REQUIRED")
            assertError(f.vote(f.outsider, f.open, """{"going":false}""", 403), "NOT_MEMBER")
            assertError(f.vote(f.actor, f.started, """{"going":false}""", 409), "MEETING_ALREADY_STARTED")
            assertError(f.vote(f.actor, f.closed, """{"going":false}""", 409), "PROPOSAL_CLOSED")
            assertError(f.vote(f.actor, UUID.randomUUID(), """{"going":false}""", 404), "RESOURCE_GONE")
            assertError(f.vote(f.actor, f.open, """{"going":false,"userId":"${f.peer}"}""", 422), "INVALID_FIELD")
            val noVote = f.vote(f.witness, f.open, """{"going":false}""", 200)
            assertEquals(JsonObject(booked.toMutableMap().apply { put("viewerGoing", JsonPrimitive(false)) }), noVote)
            assertEquals(initial, f.state(), "Rejected and absent-vote requests must not mutate any business state")

            f.installFault()
            try {
                f.assertDeferredFlags()
                assertError(f.vote(f.actor, f.open, """{"going":false}""", 500), "INTERNAL_ERROR")
                assertEquals(initial, f.state(), "Actual COMMIT failure must restore votes, booking, revision, timestamps, SYSTEM/seq, notifications/jobs and outcomes")
                assertEquals(booked, f.read(f.actor, f.open))
            } finally { f.dropFault() }

            val cancelled = f.vote(f.actor, f.open, """{"going":false}""", 200)
            assertEquals("voting", cancelled["status"]!!.jsonPrimitive.content)
            assertEquals(1, cancelled["goingCount"]!!.jsonPrimitive.int)
            assertEquals(false, cancelled["viewerGoing"]!!.jsonPrimitive.boolean)
            assertEquals(3L, cancelled["revision"]!!.jsonPrimitive.long)
            val booking = cancelled["booking"]!!.jsonObject
            val previous = booked["booking"]!!.jsonObject
            assertEquals(f.booking.toString(), booking["id"]!!.jsonPrimitive.content)
            for (field in listOf("id", "proposalId", "bookedByUserId", "bookingOrder", "bookedAt")) assertEquals(previous.getValue(field), booking.getValue(field))
            assertEquals("cancelled", booking["status"]!!.jsonPrimitive.content)
            assertEquals("insufficient_votes", booking["cancelReason"]!!.jsonPrimitive.content)
            val cancelledAt = booking.getValue("cancelledAt")
            assertNotEquals(JsonNull, cancelledAt)
            Instant.parse(cancelledAt.jsonPrimitive.content)
            assertEquals(cancelled, f.read(f.actor, f.open))
            assertEquals(listOf(f.peer), f.voters(f.open))
            f.assertEffects()
            val settled = f.state()
            assertEquals(cancelled, f.vote(f.actor, f.open, """{"going":false}""", 200))
            assertEquals(settled, f.state(), "Desired repeat with reused transport key is a no-op, not another cancellation")
            val added = f.vote(f.actor, f.open, """{"going":true}""", 200)
            assertEquals("ready_to_book", added["status"]!!.jsonPrimitive.content)
            assertEquals(4L, added["revision"]!!.jsonPrimitive.long)
            assertEquals(booking, added["booking"])
            val removedAgain = f.vote(f.actor, f.open, """{"going":false}""", 200)
            assertEquals(5L, removedAgain["revision"]!!.jsonPrimitive.long)
            assertEquals(booking, removedAgain["booking"], "Replayed desired state must not rewrite old cancellation history")
            assertEquals(removedAgain, f.read(f.actor, f.open))
            f.assertEffects()
            val final = f.state()
            assertEquals(removedAgain, f.vote(f.actor, f.open, """{"going":false}""", 200))
            assertEquals(final, f.state())
        }
    }
    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_cancel_unvote$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        private val secret = "m4-vote-write-test-secret-at-least-32-bytes"
        private val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL, databaseLockTimeoutMs = 10_000, databaseStatementTimeoutMs = 20_000)).start()
        val actor = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val witness = UUID.randomUUID()
        private val people = listOf(actor, peer, witness, outsider)
        private val sessions = people.associateWith { UUID.randomUUID() }
        private val point = UUID.randomUUID()
        private val chat = UUID.randomUUID()
        val open = UUID.randomUUID()
        val started = UUID.randomUUID()
        val closed = UUID.randomUUID()
        val booking = UUID.randomUUID()
        private val installation = UUID.randomUUID()
        private val reminder = UUID.randomUUID()
        private val reminderJob = UUID.randomUUID()
        private val fault = "m4_unvote_commit_" + actor.toString().replace("-", "")
        private val replayKey = UUID.randomUUID()
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val access = SessionAccessService(secret, JdbcSessionRepository(runtime))
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            configureTransport()
            configureRouting(runtime, sessionAccess = access,
                proposalService = ProposalService(runtime, secret), bookingService = BookingService(runtime))
        }
        private var port = 0

        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Vote fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic vote fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, actor)
                    val topic = UUID.randomUUID()
                    execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in listOf(actor, peer, witness)) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    for (id in listOf(open, started, closed)) {
                        val offset = if (id == started) "-2 hours" else "1 day"
                        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                            VALUES (?,?,?,clock_timestamp()+?::interval,clock_timestamp()+?::interval+interval '1 hour',clock_timestamp(),clock_timestamp())""", id, chat, actor, offset, offset)
                    }
                    execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", closed)
                    for (id in listOf(open, started, closed)) {
                        execute(c, "UPDATE meeting_proposals SET revision=2 WHERE id=?", id)
                        for (voter in listOf(actor, peer)) execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", id, voter)
                        execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())",
                            if (id == open) booking else UUID.randomUUID(), id, actor)
                    }
                    // Synthetic installation is never sent to a provider; it enables real transactional job writes.
                    execute(c, "INSERT INTO device_registrations(installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at) VALUES (?,?,'IOS_APNS','synthetic-never-delivered-ciphertext',?,clock_timestamp(),clock_timestamp())",
                        installation, peer, installation.toString().replace("-", "").repeat(2))
                    execute(c, """INSERT INTO notification_events(id,recipient_user_id,channel,event_type,dedupe_key,point_id,chat_id,proposal_id,booking_id,display_snapshot,visible_in_app,push_requested,created_at)
                        VALUES (?,?,'REMINDERS','reminder_1h',?,?,?, ?,?,'{}'::jsonb,true,true,clock_timestamp())""",
                        reminder, peer, "fixture-reminder:$booking", point, chat, open, booking)
                    execute(c, "INSERT INTO push_delivery_jobs(id,notification_event_id,installation_id,next_attempt_at) VALUES (?,?,?,clock_timestamp())", reminderJob, reminder, installation)
                    c.commit()
                }
                server.start(wait = false)
                port = runBlocking { server.engine.resolvedConnectors().single().port }
            } catch (failure: Throwable) { server.stop(0, 1000); runtime.close(); throw failure }
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        private fun request(actor: UUID?, id: UUID, body: String?, expected: Int): JsonObject {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/proposals/$id${if (body == null) "" else "/vote"}"))
                .timeout(Duration.ofSeconds(20))
            if (actor != null) {
                val now = Instant.now()
                builder.header("Authorization", "Bearer " + JwtTokenIssuer(secret).issue(actor, sessions.getValue(actor), 0,
                    now.minusSeconds(1), now.plusSeconds(3600)))
            }
            val request = if (body == null) builder.GET().build() else builder.header("Content-Type", "application/json")
                .header("Idempotency-Key", replayKey.toString()).PUT(HttpRequest.BodyPublishers.ofString(body)).build()
            val result = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(expected, result.statusCode(), "Synthetic vote DTO=$body")
            return Json.parseToJsonElement(result.body()).jsonObject
        }
        fun vote(actor: UUID?, id: UUID, body: String, expected: Int) = request(actor, id, body, expected)
        fun read(actor: UUID, id: UUID) = request(actor, id, null, 200)
        fun voters(id: UUID): List<UUID> = connection().use { c ->
            c.prepareStatement("SELECT user_id FROM votes WHERE proposal_id=? ORDER BY user_id").use { s ->
                s.setObject(1, id); s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }
        }
        fun state(): List<List<List<String?>>> = connection().use { c ->
            fun rows(sql: String, id: UUID = chat): List<List<String?>> = c.prepareStatement(sql).use { s ->
                s.setObject(1, id); s.executeQuery().use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
            }
            listOf(
                rows("SELECT b.* FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id=? ORDER BY b.id"),
                rows("SELECT v.* FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=? ORDER BY v.proposal_id,v.user_id"),
                rows("SELECT * FROM meeting_proposals WHERE chat_id=? ORDER BY id"),
                rows("SELECT updated_at FROM points WHERE id=?", point),
                rows("SELECT * FROM messages WHERE chat_id=? ORDER BY id"),
                rows("SELECT last_message_seq FROM chats WHERE id=?"),
                rows("SELECT * FROM notification_events WHERE chat_id=? ORDER BY id"),
                rows("SELECT j.* FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.chat_id=? ORDER BY j.id"),
                rows("SELECT operation_type,idempotency_key,outcome,result_ref,response_snapshot,created_at FROM operation_idempotency WHERE actor_user_id=? ORDER BY operation_type,idempotency_key", actor))
        }
        fun installFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE FUNCTION $fault() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic deferred unvote COMMIT fault'' USING ERRCODE=''23514''; RETURN NEW; END;'")
            s.execute("CREATE CONSTRAINT TRIGGER $fault AFTER UPDATE ON bookings DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id='$booking'::uuid AND NEW.status='CANCELLED') EXECUTE FUNCTION $fault()")
        } }
        fun assertDeferredFlags() = connection().use { c ->
            c.prepareStatement("SELECT tgdeferrable,tginitdeferred FROM pg_trigger WHERE tgname=? AND tgrelid='bookings'::regclass").use { s ->
                s.setString(1, fault); s.executeQuery().use { r -> assertTrue(r.next()); assertTrue(r.getBoolean(1)); assertTrue(r.getBoolean(2)); assertFalse(r.next()) }
            }
        }
        fun dropFault() = connection().use { c -> c.createStatement().use { s ->
            s.execute("DROP TRIGGER IF EXISTS $fault ON bookings"); s.execute("DROP FUNCTION IF EXISTS $fault()")
        } }
        fun assertEffects() = connection().use { c ->
            c.prepareStatement("""SELECT
                (SELECT count(*) FROM messages WHERE chat_id=? AND system_type='meeting_booking_cancelled' AND system_payload->>'bookingId'=?),
                (SELECT count(*) FROM notification_events WHERE booking_id=? AND event_type='booking_cancelled'),
                (SELECT status FROM push_delivery_jobs WHERE id=?),
                (SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?),
                (SELECT count(*) FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.booking_id=? AND e.event_type='booking_cancelled')""").use { s ->
                s.setObject(1, chat); s.setString(2, booking.toString()); s.setObject(3, booking); s.setObject(4, reminderJob); s.setObject(5, actor); s.setObject(6, booking)
                s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(1, r.getInt(1)); assertEquals(2, r.getInt(2)); assertEquals("CANCELLED", r.getString(3)); assertEquals(0, r.getInt(4)); assertEquals(1, r.getInt(5)) }
            }
        }
        override fun close() {
            try {
                server.stop(0, 1000)
                dropFault()
                connection().use { c ->
                    c.autoCommit = false
                    execute(c, "DELETE FROM points WHERE id=?", point)
                    for (id in people) execute(c, "DELETE FROM users WHERE id=?", id)
                    c.commit()
                }
            } finally { runtime.close() }
        }
    }

    companion object {
        private fun assertError(body: JsonObject, code: String) { assertEquals(code, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content) }
        private fun execute(c: Connection, sql: String, vararg values: Any) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}
