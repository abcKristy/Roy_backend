package com.example.meetings

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.SessionAccessService
import com.example.persistence.JdbcSessionRepository
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class MeetingAcceptanceFixtureIntegrationTest {
    @Test
    fun `real fixture isolates two same point topics members intervals replay and controlled creation time`() = runBlocking<Unit> {
        MeetingAcceptanceFixture().use { f ->
            assertEquals(3, f.members.distinct().size)
            assertFalse(f.outsideIsMember())
            assertEquals(ZoneOffset.UTC, f.clock.zone)
            val (a, b, c, d) = f.slots
            assertTrue(a.overlaps(b)); assertTrue(b.overlaps(c)); assertFalse(a.overlaps(c))
            for (slot in listOf(a, b, c)) assertFalse(slot.overlaps(d))
            f.connection().use { connection ->
                connection.prepareStatement("SELECT count(DISTINCT t.point_id),count(DISTINCT t.id),count(m.user_id) FROM point_topics t JOIN chats c ON c.point_topic_id=t.id JOIN chat_memberships m ON m.chat_id=c.id WHERE t.point_id=?").use { s ->
                    s.setObject(1, f.point)
                    s.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(2, rows.getInt(2)); assertEquals(6, rows.getInt(3)) }
                }
            }
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport()
                configureRouting(f.runtime, sessionAccess = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime)),
                    proposalService = f.proposals(), bookingService = f.bookings())
            }
            server.start(wait = false)
            try {
                val wire = Wire(server.engine.resolvedConnectors().single().port, f)
                for ((index, slot) in f.slots.withIndex()) {
                    val chat = f.chats[index % 2]
                    val body = slot.body()
                    val baseline = f.state()
                    for (actor in listOf<UUID?>(f.outsider, null)) {
                        val denied = wire.request(actor, "/api/v1/chats/$chat/proposals", body)
                        assertEquals(if (actor == null) 401 else 403, denied.statusCode())
                        assertEquals(if (actor == null) "AUTH_REQUIRED" else "NOT_MEMBER", denied.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    }
                    assertEquals(baseline, f.state())
                    val key = UUID.randomUUID()
                    val created = wire.request(f.author, "/api/v1/chats/$chat/proposals", body, key)
                    assertEquals(201, created.statusCode())
                    val snapshot = created.json()
                    assertEquals(chat.toString(), snapshot["chatId"]!!.jsonPrimitive.content)
                    assertEquals(slot.startsAt.toString(), snapshot["startsAt"]!!.jsonPrimitive.content)
                    assertEquals(slot.endsAt.toString(), snapshot["endsAt"]!!.jsonPrimitive.content)
                    assertEquals(0, snapshot["goingCount"]!!.jsonPrimitive.int)
                    assertEquals(0L, snapshot["revision"]!!.jsonPrimitive.long)
                    assertEquals(false, snapshot["viewerGoing"]!!.jsonPrimitive.boolean)
                    val id = snapshot["id"]!!.jsonPrimitive.content
                    f.connection().use { connection ->
                        connection.prepareStatement("SELECT starts_at,ends_at,chat_id FROM meeting_proposals WHERE id=?").use { s ->
                            s.setObject(1, UUID.fromString(id)); s.executeQuery().use { rows ->
                                assertTrue(rows.next()); assertEquals(slot.startsAt, rows.getObject(1, OffsetDateTime::class.java).toInstant())
                                assertEquals(slot.endsAt, rows.getObject(2, OffsetDateTime::class.java).toInstant()); assertEquals(chat, rows.getObject(3))
                            }
                        }
                    }
                    val stable = f.state()
                    repeat(2) {
                        assertEquals(snapshot, wire.request(f.author, "/api/v1/chats/$chat/proposals", body, key).also { assertEquals(201, it.statusCode()) }.json())
                        for (member in f.members) assertEquals(snapshot, wire.request(member, "/api/v1/proposals/$id").also { assertEquals(200, it.statusCode()) }.json())
                    }
                    assertEquals(stable, f.state())
                }
            } finally { server.stop(0, 1000) }

            // Opt-in boundary context: never inject this clock into the ordinary chain above.
            f.clock.set(a.startsAt)
            val boundaryServer = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport()
                configureRouting(f.runtime, sessionAccess = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime)),
                    proposalService = f.proposals(controlledTime = true), bookingService = f.bookings(controlledCloseTime = true))
            }
            boundaryServer.start(wait = false)
            try {
                val wire = Wire(boundaryServer.engine.resolvedConnectors().single().port, f)
                val path = "/api/v1/chats/${f.chats[0]}/proposals"
                val baseline = f.state()
                val rejected = wire.request(f.author, path, a.body())
                assertEquals(422, rejected.statusCode()); assertEquals("INVALID_FIELD", rejected.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertEquals(baseline, f.state())
                val key = UUID.randomUUID()
                val accepted = wire.request(f.author, path, b.body(), key)
                assertEquals(201, accepted.statusCode())
                assertEquals(a.startsAt, java.time.Instant.parse(accepted.json()["createdAt"]!!.jsonPrimitive.content))
                f.clock.set(b.endsAt.plusSeconds(1))
                val stable = f.state()
                val replay = wire.request(f.author, path, b.body(), key)
                assertEquals(201, replay.statusCode()); assertEquals(accepted.json(), replay.json())
                assertEquals(422, wire.request(f.author, path, b.body()).statusCode())
                assertEquals(stable, f.state())
            } finally { boundaryServer.stop(0, 1000) }
        }
    }

    @Test
    fun `failure after committed fixture seed removes only its UUID namespace`() {
        val failure = IllegalStateException("synthetic fixture setup failure")
        var point: UUID? = null
        var people = emptyList<UUID>()
        val thrown = assertFailsWith<IllegalStateException> {
            MeetingAcceptanceFixture { f -> point = f.point; people = f.people; throw failure }
        }
        assertSame(failure, thrown)
        DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"), System.getenv("DATABASE_USER"), System.getenv("DATABASE_PASSWORD")).use { c ->
            c.prepareStatement("SELECT (SELECT count(*) FROM points WHERE id=?)+(SELECT count(*) FROM users WHERE id IN (?,?,?,?))").use { s ->
                (listOf(checkNotNull(point)) + people).forEachIndexed { index, id -> s.setObject(index + 1, id) }
                s.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
            }
        }
    }

    private fun MeetingAcceptanceFixture.outsideIsMember(): Boolean = connection().use { c ->
        c.prepareStatement("SELECT EXISTS(SELECT 1 FROM chat_memberships WHERE user_id=? AND chat_id IN (?,?))").use { s ->
            listOf(outsider, chats[0], chats[1]).forEachIndexed { index, id -> s.setObject(index + 1, id) }
            s.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
        }
    }
    private fun MeetingAcceptanceFixture.Slot.body() = buildJsonObject { put("startsAt", startsAt.toString()); put("endsAt", endsAt.toString()) }
    private fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject
    private class Wire(port: Int, private val f: MeetingAcceptanceFixture) {
        private val origin = "http://127.0.0.1:$port"
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        fun request(actor: UUID?, path: String, body: JsonObject? = null, key: UUID = UUID.randomUUID()): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(origin + path)).timeout(Duration.ofSeconds(20))
            if (actor != null) builder.header("Authorization", f.bearer(actor))
            val request = if (body == null) builder.GET().build() else builder.header("Content-Type", "application/json").header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }
}