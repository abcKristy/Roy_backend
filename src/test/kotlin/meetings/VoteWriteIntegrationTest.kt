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
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class VoteWriteIntegrationTest {
    @Test
    fun `HTTP desired vote is own actor idempotent and refuses unauthorized or nonopen writes`() {
        Fixture().use { f ->
            val initial = f.state()
            val falseFirst = f.vote(f.actor, f.open, """{"going":false}""", 200)
            assertEquals(0L, falseFirst["revision"]!!.jsonPrimitive.long)
            assertEquals(initial, f.state())
            val yes = f.vote(f.actor, f.open, """{"going":true}""", 200)
            assertProposal(yes, f.open, true, 1, 1)
            assertEquals(yes, f.read(f.actor, f.open))
            assertEquals(listOf(f.actor), f.voters(f.open))
            val afterYes = f.state()
            assertEquals(yes, f.vote(f.actor, f.open, """{"going":true}""", 200))
            assertEquals(afterYes, f.state())
            // Another actor's desired false never removes the author's row.
            val peerNo = f.vote(f.peer, f.open, """{"going":false}""", 200)
            assertProposal(peerNo, f.open, false, 1, 1)
            assertEquals(listOf(f.actor), f.voters(f.open))
            assertEquals(afterYes, f.state())
            f.connection().use { c ->
                c.autoCommit = false
                try {
                    val failure = assertFailsWith<SQLException> {
                        execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", f.open, f.actor)
                    }
                    assertEquals("23505", failure.sqlState)
                } finally { c.rollback() }
            }
            for (body in listOf("""{"going":true,"userId":"${f.peer}"}""",
                """{"going":false,"expectedRevision":1}""", """{"going":"true"}""", """{"going":"false"}""",
                """{"going":null}""", """{"going":0}""", """{"going":1}""", """{"going":{}}""",
                """{"going":[]}""", "{}")) {
                assertError(f.vote(f.actor, f.open, body, 422), "INVALID_FIELD")
            }
            assertError(f.vote(f.actor, f.open, "{", 400), "INVALID_JSON")
            assertError(f.vote(null, f.open, """{"going":true}""", 401), "AUTH_REQUIRED")
            assertError(f.vote(f.outsider, f.open, """{"going":true}""", 403), "NOT_MEMBER")
            for (going in listOf(true, false)) {
                assertError(f.vote(f.actor, f.started, """{"going":$going}""", 409), "MEETING_ALREADY_STARTED")
                assertError(f.vote(f.actor, f.closed, """{"going":$going}""", 409), "PROPOSAL_CLOSED")
            }
            assertError(f.vote(f.actor, UUID.randomUUID(), """{"going":true}""", 404), "RESOURCE_GONE")
            assertEquals(afterYes, f.state())
            assertEquals(listOf(f.actor), f.voters(f.open))
            val no = f.vote(f.actor, f.open, """{"going":false}""", 200)
            assertProposal(no, f.open, false, 0, 2)
            assertEquals(no, f.read(f.actor, f.open))
            val afterNo = f.state()
            assertEquals(no, f.vote(f.actor, f.open, """{"going":false}""", 200))
            assertEquals(afterNo, f.state())
            assertTrue(f.voters(f.open).isEmpty())
        }
    }

    @Test
    fun `barrier released distinct HTTP voters retain both rows and revisions`() {
        Fixture().use { f ->
            val workers = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            try {
                val requests = listOf(f.actor, f.peer).map { actor -> workers.submit<JsonObject> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "Vote barrier timed out" }
                    f.vote(actor, f.open, """{"going":true}""", 200)
                } }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                val replies = requests.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(setOf(1L, 2L), replies.map { it["revision"]!!.jsonPrimitive.long }.toSet())
                assertEquals(setOf(1, 2), replies.map { it["goingCount"]!!.jsonPrimitive.int }.toSet())
                replies.forEach { assertTrue(it["viewerGoing"]!!.jsonPrimitive.boolean) }
                assertEquals(setOf(f.actor, f.peer), f.voters(f.open).toSet())
                val final = f.read(f.actor, f.open)
                assertProposal(final, f.open, true, 2, 2)
                val beforeRepeat = f.state()
                assertEquals(final, f.vote(f.actor, f.open, """{"going":true}""", 200))
                assertEquals(beforeRepeat, f.state())
            } finally {
                start.countDown()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private data class State(val votes: Long, val revisions: Long, val messages: Long, val notifications: Long, val sequence: Long)

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_vote$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        private val secret = "m4-vote-write-test-secret-at-least-32-bytes"
        private val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL, databaseLockTimeoutMs = 10_000, databaseStatementTimeoutMs = 20_000)).start()
        val actor = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        private val people = listOf(actor, peer, outsider)
        private val sessions = people.associateWith { UUID.randomUUID() }
        private val point = UUID.randomUUID()
        private val chat = UUID.randomUUID()
        val open = UUID.randomUUID()
        val started = UUID.randomUUID()
        val closed = UUID.randomUUID()
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
                    for (id in listOf(actor, peer)) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    for (id in listOf(open, started, closed)) {
                        val offset = if (id == started) "-2 hours" else "1 day"
                        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                            VALUES (?,?,?,clock_timestamp()+?::interval,clock_timestamp()+?::interval+interval '1 hour',clock_timestamp(),clock_timestamp())""", id, chat, actor, offset, offset)
                    }
                    execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", closed)
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
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build()
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
        fun state(): State = connection().use { c ->
            fun count(sql: String): Long = c.prepareStatement(sql).use { s ->
                s.setObject(1, chat); s.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
            }
            State(count("SELECT count(*) FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id=?"),
                count("SELECT COALESCE(sum(revision),0) FROM meeting_proposals WHERE chat_id=?"),
                count("SELECT count(*) FROM messages WHERE chat_id=?"), count("SELECT count(*) FROM notification_events WHERE chat_id=?"),
                count("SELECT last_message_seq FROM chats WHERE id=?"))
        }
        override fun close() {
            try {
                server.stop(0, 1000)
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
        private fun assertProposal(body: JsonObject, id: UUID, going: Boolean, count: Int, revision: Long) {
            assertEquals(setOf("id", "chatId", "proposedByUserId", "startsAt", "endsAt", "status", "goingCount", "viewerGoing", "booking", "revision", "createdAt", "updatedAt"), body.keys)
            assertEquals(id.toString(), body["id"]!!.jsonPrimitive.content)
            assertEquals(going, body["viewerGoing"]!!.jsonPrimitive.boolean)
            assertEquals(count, body["goingCount"]!!.jsonPrimitive.int)
            assertEquals(revision, body["revision"]!!.jsonPrimitive.long)
            assertEquals(JsonNull, body["booking"])
            assertEquals(if (count > 1) "ready_to_book" else "voting", body["status"]!!.jsonPrimitive.content)
        }
        private fun execute(c: Connection, sql: String, vararg values: Any) {
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
                s.executeUpdate()
            }
        }
    }
}
