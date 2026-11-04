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
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class VoterPageIntegrationTest {
    @Test
    fun `bounded public voter pages retain tie order and reject foreign or invalid contexts`() = runBlocking<Unit> {
        Fixture().use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport()
                configureRouting(f.runtime, sessionAccess = access,
                    proposalService = ProposalService(f.runtime, f.secret), bookingService = BookingService(f.runtime))
            }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                fun get(actor: UUID?, path: String, expected: Int = 200): JsonObject {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(15))
                    if (actor != null) builder.header("Authorization", f.bearer(actor))
                    val reply = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(expected, reply.statusCode(), "Synthetic voter endpoint=$path")
                    val body = Json.parseToJsonElement(reply.body()).jsonObject
                    assertFalse(body.toString().contains("synthetic-private-password-hash"))
                    assertFalse(body.toString().contains("\"ageYears\""))
                    assertFalse(body.toString().contains("\"phone"))
                    for (id in f.people) {
                        assertFalse(body.toString().contains(f.phone(id)))
                        assertFalse(body.toString().contains(f.avatarKey(id)))
                    }
                    return body
                }
                fun votes(actor: UUID?, proposal: UUID = f.proposal, query: String = "", expected: Int = 200) =
                    get(actor, "/api/v1/proposals/$proposal/votes$query", expected)
                fun reject(actor: UUID?, proposal: UUID = f.proposal, query: String = "", status: Int, code: String) {
                    val error = votes(actor, proposal, query, status)
                    assertEquals(setOf("error", "requestId"), error.keys)
                    assertEquals(code, error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertFalse(error.toString().contains("displayName"))
                    for (id in f.voters) assertFalse(error.toString().contains(id.toString()))
                }
                val before = f.state()
                val first = votes(f.viewer, query = "?limit=2")
                assertEquals(setOf("items", "nextCursor"), first.keys)
                assertEquals(2, first["items"]!!.jsonArray.size)
                val cursor = first["nextCursor"]!!.jsonPrimitive.content
                val second = votes(f.viewer, query = "?limit=2&cursor=$cursor")
                val third = votes(f.viewer, query = "?limit=2&cursor=${second["nextCursor"]!!.jsonPrimitive.content}")
                assertEquals(JsonNull, third["nextCursor"])
                val rows = (first["items"]!!.jsonArray + second["items"]!!.jsonArray + third["items"]!!.jsonArray).map { it.jsonObject }
                assertEquals(f.voters.map(UUID::toString), rows.map { it["user"]!!.jsonObject["id"]!!.jsonPrimitive.content })
                assertEquals(f.voters.size, rows.map { it["user"]!!.jsonObject["id"] }.toSet().size)
                for ((index, row) in rows.withIndex()) {
                    val id = f.voters[index]
                    assertEquals(setOf("proposalId", "user", "createdAt"), row.keys)
                    assertEquals(f.proposal.toString(), row["proposalId"]!!.jsonPrimitive.content)
                    assertEquals(f.voteTime(index).toString(), row["createdAt"]!!.jsonPrimitive.content)
                    assertEquals(buildJsonObject {
                        put("id", id.toString()); put("displayName", f.displayName(id))
                        put("avatarUrl", if (index % 2 == 0) JsonPrimitive("/api/v1/users/$id/avatar") else JsonNull)
                    }, row["user"])
                }
                assertEquals(first, votes(f.viewer, query = "?limit=2"))
                assertEquals(rows, votes(f.viewer)["items"]!!.jsonArray.map { it.jsonObject })
                assertEquals(rows, votes(f.peer)["items"]!!.jsonArray.map { it.jsonObject })
                assertEquals(1, votes(f.viewer, query = "?limit=1")["items"]!!.jsonArray.size)
                assertEquals(5, votes(f.viewer, query = "?limit=100")["items"]!!.jsonArray.size)
                assertTrue(votes(f.viewer, f.sameChatProposal)["items"]!!.jsonArray.isEmpty())
                assertTrue(votes(f.viewer, f.otherChatProposal)["items"]!!.jsonArray.isEmpty())
                reject(null, status = 401, code = "AUTH_REQUIRED")
                reject(f.outsider, status = 403, code = "NOT_MEMBER")
                for (limit in listOf("0", "-1", "101", "bad", "1.5", "2147483648")) {
                    reject(f.viewer, query = "?limit=$limit", status = 400, code = "INVALID_FIELD")
                }
                reject(f.viewer, query = "?limit=2&cursor=invalid", status = 400, code = "INVALID_CURSOR")
                val tampered = (if (cursor.first() == 'A') "B" else "A") + cursor.drop(1)
                reject(f.viewer, query = "?limit=2&cursor=$tampered", status = 400, code = "INVALID_CURSOR")
                reject(f.peer, query = "?limit=2&cursor=$cursor", status = 400, code = "INVALID_CURSOR")
                reject(f.viewer, query = "?limit=3&cursor=$cursor", status = 400, code = "INVALID_CURSOR")
                reject(f.viewer, f.sameChatProposal, "?limit=2&cursor=$cursor", 400, "INVALID_CURSOR")
                reject(f.viewer, f.otherChatProposal, "?limit=2&cursor=$cursor", 400, "INVALID_CURSOR")
                val otherScope = get(f.viewer, "/api/v1/chats/${f.chats[0]}/proposals?limit=1")["nextCursor"]!!.jsonPrimitive.content
                reject(f.viewer, query = "?limit=1&cursor=$otherScope", status = 400, code = "INVALID_CURSOR")
                assertEquals(before, f.state(), "Pages and rejected cursors must not change stored votes or effects")
            } finally { server.stop(0, 1000) }
        }
    }

    private data class State(val votes: Long, val revisions: Long, val messages: Long, val notifications: Long, val sequence: Long)

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_voters$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-voter-page-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL)).start()
        val viewer = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        // Equal timestamps cross a page boundary; positive high bits fix SQL UUID descending order.
        val voters = listOf(UUID.randomUUID(), UUID(3L, UUID.randomUUID().leastSignificantBits),
            UUID(2L, UUID.randomUUID().leastSignificantBits), UUID(1L, UUID.randomUUID().leastSignificantBits), UUID.randomUUID())
        val people = listOf(viewer, peer, outsider) + voters
        private val sessions = listOf(viewer, peer, outsider).associateWith { UUID.randomUUID() }
        private val point = UUID.randomUUID()
        val chats = List(2) { UUID.randomUUID() }
        val proposal = UUID.randomUUID()
        val sameChatProposal = UUID.randomUUID()
        val otherChatProposal = UUID.randomUUID()
        fun phone(id: UUID) = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        fun avatarKey(id: UUID) = "private-voter-avatar/$id.png"
        fun displayName(id: UUID) = "Public voter ${people.indexOf(id)}"
        fun voteTime(index: Int): Instant = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(when (index) { 0 -> 30; 4 -> 10; else -> 20 })

        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,avatar_object_key,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-private-password-hash',?,37,?,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone(id), displayName(id),
                            if (voters.indexOf(id).takeIf { it >= 0 }?.rem(2) == 0) avatarKey(id) else null)
                        sessions[id]?.let { session -> execute(c,
                            "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2)) }
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic voter fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, viewer)
                    for ((index, chat) in chats.withIndex()) {
                        val topic = UUID.randomUUID()
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())", topic, point, listOf("football", "basketball")[index])
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                        for (member in listOf(viewer, peer) + if (index == 0) voters else emptyList()) {
                            execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, member)
                        }
                    }
                    for ((id, chat) in listOf(proposal to chats[0], sameChatProposal to chats[0], otherChatProposal to chats[1])) {
                        execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                            VALUES (?,?,?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '25 hours',clock_timestamp(),clock_timestamp())""", id, chat, viewer)
                    }
                    for ((index, id) in voters.withIndex()) {
                        execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,?)", proposal, id, voteTime(index).atOffset(ZoneOffset.UTC))
                    }
                    c.commit()
                }
            } catch (failure: Throwable) { runtime.close(); throw failure }
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun bearer(actor: UUID): String {
            val now = Instant.now()
            return "Bearer " + JwtTokenIssuer(secret).issue(actor, sessions.getValue(actor), 0, now.minusSeconds(1), now.plusSeconds(3600))
        }
        fun state(): State = connection().use { c ->
            fun count(sql: String): Long = c.prepareStatement(sql).use { s ->
                s.setObject(1, chats[0]); s.setObject(2, chats[1])
                s.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
            }
            State(count("SELECT count(*) FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id IN (?,?)"),
                count("SELECT COALESCE(sum(revision),0) FROM meeting_proposals WHERE chat_id IN (?,?)"),
                count("SELECT count(*) FROM messages WHERE chat_id IN (?,?)"), count("SELECT count(*) FROM notification_events WHERE chat_id IN (?,?)"),
                count("SELECT COALESCE(sum(last_message_seq),0) FROM chats WHERE id IN (?,?)"))
        }
        override fun close() {
            try {
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
        private fun execute(c: Connection, sql: String, vararg values: Any?) {
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate()
            }
        }
    }
}
