package com.example.meetings

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.SessionAccessService
import com.example.chats.ChatReadService
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MeetingRestChainIntegrationTest {
    @Test
    fun `persisted meeting chain agrees across native clients websocket and reconnect recovery`() = runBlocking<Unit> {
        MeetingAcceptanceFixture().use { f ->
            val access = SessionAccessService(f.secret, JdbcSessionRepository(f.runtime))
            val hub = RealtimeHub(f.runtime, access)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureTransport(); installRealtimeTransport()
                configureRouting(f.runtime, sessionAccess = access, realtimeHub = hub,
                    proposalService = f.proposals(), bookingService = f.bookings(), chatReadService = ChatReadService(f.runtime, f.secret))
            }
            server.start(wait = false)
            try {
                Chain(f, server.engine.resolvedConnectors().single().port, hub).use { chain -> chain.exercise() }
            } finally { server.stop(0, 1000) }
        }
    }

    private class Chain(private val f: MeetingAcceptanceFixture, port: Int, private val hub: RealtimeHub) : AutoCloseable {
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val origin = "http://127.0.0.1:$port"
        private val wsOrigin = "ws://127.0.0.1:$port/api/v1/ws"
        private val sockets = mutableListOf<WebSocket>()
        private val ledger = mutableListOf<JsonObject>()
        private lateinit var authorWire: Wire
        private var voterWire: Wire? = null
        private lateinit var observerWire: Wire
        private lateinit var outsiderWire: Wire
        private lateinit var voterSocket: WebSocket

        suspend fun exercise() {
            authorWire = connect(f.author, f.chats).first
            connect(f.voter, f.chats).also { voterWire = it.first; voterSocket = it.second }
            observerWire = connect(f.observer, listOf(f.chats[1])).first
            outsiderWire = connect(f.outsider, f.chats, accepted = false).first
            val a = create(f.chats[0], f.slots[0])
            val aid = a.id()
            verify(a, 0, 0, "voting", emptySet()); events(a, listOf("meeting_proposed"))
            deny(f.outsider, "PUT", "/api/v1/proposals/$aid/vote", vote(true), 403, "NOT_MEMBER")
            deny(f.outsider, "GET", "/api/v1/proposals/$aid", null, 403, "NOT_MEMBER")
            deny(f.observer, "POST", "/api/v1/proposals/$aid/booking", booking(0), 409, "VOTE_REQUIRED")
            val a1 = mutate(f.author, "PUT", "/api/v1/proposals/$aid/vote", vote(true), 200)
            verify(a1, 1, 1, "voting", setOf(f.author)); events(a1)
            deny(f.author, "POST", "/api/v1/proposals/$aid/booking", booking(1), 409, "INSUFFICIENT_VOTES")
            val ready = mutate(f.voter, "PUT", "/api/v1/proposals/$aid/vote", vote(true), 200)
            verify(ready, 2, 2, "ready_to_book", setOf(f.author, f.voter)); events(ready, listOf("meeting_ready"))
            deny(f.observer, "POST", "/api/v1/proposals/$aid/booking", booking(2), 409, "VOTE_REQUIRED")
            deny(f.voter, "POST", "/api/v1/proposals/$aid/close", close(2), 403, "FORBIDDEN")
            val firstKey = UUID.randomUUID()
            val bookedA = mutate(f.voter, "POST", "/api/v1/proposals/$aid/booking", booking(2), 201, firstKey)
            verify(bookedA, 3, 2, "booked", setOf(f.author, f.voter)); events(bookedA, listOf("meeting_booked"), bookingChanged = true)
            val oldBooking = bookedA["booking"]!!.jsonObject
            assertEquals(f.voter.toString(), oldBooking["bookedByUserId"]!!.jsonPrimitive.content)
            assertNotEquals(f.author.toString(), oldBooking["bookedByUserId"]!!.jsonPrimitive.content)
            unchanged(f.voter, "POST", "/api/v1/proposals/$aid/booking", booking(2), 201, firstKey, bookedA)

            val b = create(f.chats[1], f.slots[1]); val bid = b.id()
            verify(b, 0, 0, "voting", emptySet()); events(b, listOf("meeting_proposed"))
            val b1 = mutate(f.author, "PUT", "/api/v1/proposals/$bid/vote", vote(true), 200)
            verify(b1, 1, 1, "voting", setOf(f.author)); events(b1)
            val b2 = mutate(f.voter, "PUT", "/api/v1/proposals/$bid/vote", vote(true), 200)
            verify(b2, 2, 2, "ready_to_book", setOf(f.author, f.voter)); events(b2, listOf("meeting_ready"))
            val overlapKey = UUID.randomUUID()
            val warning = deny(f.author, "POST", "/api/v1/proposals/$bid/booking", booking(2), 409, "OVERLAP_CONFIRM_REQUIRED", overlapKey)
            val overlaps = warning["error"]!!.jsonObject["details"]!!.jsonObject["overlaps"]!!.jsonArray
            assertEquals(listOf(oldBooking["id"]), overlaps.map { it.jsonObject["bookingId"] })
            val bookedB = mutate(f.author, "POST", "/api/v1/proposals/$bid/booking", booking(2, true), 201, overlapKey)
            verify(bookedB, 3, 2, "booked", setOf(f.author, f.voter)); events(bookedB, listOf("meeting_booked"), bookingChanged = true)
            val bBooking = bookedB["booking"]!!.jsonObject
            assertTrue(bBooking["bookingOrder"]!!.jsonPrimitive.long > oldBooking["bookingOrder"]!!.jsonPrimitive.long)
            assertEquals(listOf(oldBooking["id"]), bBooking["overlappingActive"]!!.jsonArray.map { it.jsonObject["bookingId"] })
            val currentA = get(f.author, "/api/v1/proposals/$aid")
            assertEquals(listOf(bBooking["id"]), currentA["booking"]!!.jsonObject["overlappingActive"]!!.jsonArray.map { it.jsonObject["bookingId"] })

            // A real disconnected member deliberately misses the next committed cancellation.
            val lastSeen = get(f.voter, "/api/v1/chats/${f.chats[0]}/messages?afterSeq=0")["items"]!!.jsonArray.last().jsonObject["messageSeq"]!!.jsonPrimitive.long
            voterSocket.sendClose(WebSocket.NORMAL_CLOSURE, "synthetic event loss").get(5, TimeUnit.SECONDS)
            assertEquals(WebSocket.NORMAL_CLOSURE, voterWire!!.closed.get(5, TimeUnit.SECONDS)); voterWire = null
            val cancelledA = mutate(f.voter, "PUT", "/api/v1/proposals/$aid/vote", vote(false), 200)
            verify(cancelledA, 4, 1, "voting", setOf(f.author))
            val missed = events(cancelledA, listOf("meeting_booking_cancelled"), bookingChanged = true)
            val cancelledHistory = cancelledA["booking"]!!.jsonObject
            assertEquals(oldBooking["id"], cancelledHistory["id"])
            assertEquals("cancelled", cancelledHistory["status"]!!.jsonPrimitive.content)
            assertEquals("insufficient_votes", cancelledHistory["cancelReason"]!!.jsonPrimitive.content)
            val oldRow = bookingRow(oldBooking["id"]!!.jsonPrimitive.content)
            assertTrue(get(f.author, "/api/v1/proposals/$bid")["booking"]!!.jsonObject["overlappingActive"]!!.jsonArray.isEmpty())
            connect(f.voter, f.chats).also { voterWire = it.first; voterSocket = it.second }
            silence() // Subscribe has no business-event replay; recover via real REST.
            val stableRecovery = f.state()
            val recovered = get(f.voter, "/api/v1/proposals/$aid")
            assertEquals(cancelledA, recovered)
            val summaries = get(f.voter, "/api/v1/me/chats")["items"]!!.jsonArray
            assertEquals(f.chats.toSet(), summaries.map { UUID.fromString(it.jsonObject["id"]!!.jsonPrimitive.content) }.toSet())
            val detail = get(f.voter, "/api/v1/chats/${f.chats[0]}")
            assertTrue(detail.toString().contains(aid))
            val delta = get(f.voter, "/api/v1/chats/${f.chats[0]}/messages?afterSeq=$lastSeen&limit=100")
            val missedMessages = missed.filter { it["type"]!!.jsonPrimitive.content == "chat.message.created" }.map { it["payload"]!! }
            assertEquals(missedMessages, delta["items"]!!.jsonArray.toList())
            assertEquals(JsonNull, delta["nextAfterSeq"])
            assertEquals(stableRecovery, f.state())
            ledger += buildJsonObject { put("step", "disconnected member authoritative recovery"); put("afterSeq", lastSeen); put("proposal", recovered); put("delta", delta); put("db", dbProof()) }

            val readyAgain = mutate(f.voter, "PUT", "/api/v1/proposals/$aid/vote", vote(true), 200)
            verify(readyAgain, 5, 2, "ready_to_book", setOf(f.author, f.voter)); events(readyAgain, listOf("meeting_ready"))
            unchanged(f.voter, "PUT", "/api/v1/proposals/$aid/vote", vote(true), 200, null, readyAgain)
            val rebookKey = UUID.randomUUID()
            val rebooked = mutate(f.author, "POST", "/api/v1/proposals/$aid/booking", booking(5, true), 201, rebookKey)
            verify(rebooked, 6, 2, "booked", setOf(f.author, f.voter)); events(rebooked, listOf("meeting_booked"), bookingChanged = true)
            val newBooking = rebooked["booking"]!!.jsonObject
            assertNotEquals(oldBooking["id"], newBooking["id"])
            assertTrue(newBooking["bookingOrder"]!!.jsonPrimitive.long > bBooking["bookingOrder"]!!.jsonPrimitive.long)
            assertEquals(oldRow, bookingRow(oldBooking["id"]!!.jsonPrimitive.content))
            val closeKey = UUID.randomUUID()
            val closed = mutate(f.author, "POST", "/api/v1/proposals/$aid/close", close(6), 200, closeKey)
            verify(closed, 7, 2, "closed", setOf(f.author, f.voter)); events(closed, listOf("meeting_booking_cancelled", "meeting_closed"), bookingChanged = true)
            assertEquals("closed_by_author", closed["booking"]!!.jsonObject["cancelReason"]!!.jsonPrimitive.content)
            unchanged(f.author, "POST", "/api/v1/proposals/$aid/close", close(6), 200, closeKey, closed)
            // An earlier successful response is historical, and must never restore an active booking.
            unchanged(f.voter, "POST", "/api/v1/proposals/$aid/booking", booking(7, true), 201, firstKey, bookedA)
            unchanged(f.author, "POST", "/api/v1/proposals/$aid/booking", booking(0), 201, rebookKey, rebooked)
            verify(closed, 7, 2, "closed", setOf(f.author, f.voter))
            assertEquals(oldRow, bookingRow(oldBooking["id"]!!.jsonPrimitive.content))
            val finalB = get(f.author, "/api/v1/proposals/$bid")
            verify(finalB, 3, 2, "booked", setOf(f.author, f.voter))
            assertTrue(finalB["booking"]!!.jsonObject["overlappingActive"]!!.jsonArray.isEmpty())
            deny(f.author, "POST", "/api/v1/proposals/$aid/booking", booking(7), 409, "PROPOSAL_CLOSED")
            ledger += buildJsonObject { put("step", "final persisted state"); put("closedA", closed); put("activeB", finalB); put("db", dbProof()) }
            val path = Path.of(".local", "t112-evidence", "execution-ledger.json")
            Files.createDirectories(path.parent)
            Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("baseline", "9971abe3fc19e5a6511bfa0d1e9be26c83292e4b"); put("clock", "production PostgreSQL wall time");
                put("pointId", f.point.toString()); put("chatIds", JsonArray(f.chats.map { JsonPrimitive(it.toString()) })); put("steps", JsonArray(ledger))
            }))
        }

        private fun create(chat: UUID, slot: MeetingAcceptanceFixture.Slot) = mutate(f.author, "POST", "/api/v1/chats/$chat/proposals",
            buildJsonObject { put("startsAt", slot.startsAt.toString()); put("endsAt", slot.endsAt.toString()) }, 201, UUID.randomUUID())
        private fun vote(value: Boolean) = buildJsonObject { put("going", value) }
        private fun booking(revision: Long, confirm: Boolean = false) = buildJsonObject { put("expectedRevision", revision); put("confirmOverlap", confirm) }
        private fun close(revision: Long) = buildJsonObject { put("expectedRevision", revision) }
        private fun JsonObject.id() = getValue("id").jsonPrimitive.content
        private fun get(actor: UUID, path: String) = request(actor, "GET", path, null, null, 200)
        private fun mutate(actor: UUID, method: String, path: String, body: JsonObject, status: Int, key: UUID? = null): JsonObject {
            val actualKey = if (method == "POST") key ?: UUID.randomUUID() else null
            val response = request(actor, method, path, body, actualKey, status)
            ledger += buildJsonObject { put("method", method); put("path", path); put("actorId", actor.toString()); put("body", body)
                put("operationKey", actualKey?.let { JsonPrimitive(it.toString()) } ?: JsonNull); put("expectedStatus", status); put("actualStatus", status); put("response", response); put("db", dbProof()) }
            return response
        }
        private suspend fun deny(actor: UUID, method: String, path: String, body: JsonObject?, status: Int, code: String, key: UUID = UUID.randomUUID()): JsonObject {
            val stable = f.state()
            val response = request(actor, method, path, body, if (method == "POST") key else null, status)
            assertEquals(code, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals(setOf("error", "requestId"), response.keys)
            if (status == 403) { assertFalse(response.toString().contains("bookingOrder")); assertFalse(response.toString().contains("viewerGoing")) }
            assertEquals(stable, f.state()); silence()
            ledger += buildJsonObject { put("method", method); put("path", path); put("actorId", actor.toString()); put("body", body ?: JsonNull)
                put("operationKey", if (method == "POST") JsonPrimitive(key.toString()) else JsonNull); put("expectedStatus", status); put("actualStatus", status); put("expectedCode", code)
                put("actualError", response["error"]!!); put("noEffects", true); put("db", dbProof()) }
            return response
        }
        private suspend fun unchanged(actor: UUID, method: String, path: String, body: JsonObject, status: Int, key: UUID?, expected: JsonObject) {
            val stable = f.state(); assertEquals(expected, mutate(actor, method, path, body, status, key)); assertEquals(stable, f.state()); silence()
        }
        private fun request(actor: UUID, method: String, path: String, body: JsonObject?, key: UUID?, expected: Int): JsonObject {
            val builder = HttpRequest.newBuilder(URI(origin + path)).timeout(Duration.ofSeconds(20)).header("Authorization", f.bearer(actor))
            if (body != null) builder.header("Content-Type", "application/json")
            if (key != null) builder.header("Idempotency-Key", key.toString())
            val response = http.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(expected, response.statusCode(), "$method $path")
            return Json.parseToJsonElement(response.body()).jsonObject
        }

        private fun verify(value: JsonObject, revision: Long, count: Int, status: String, voters: Set<UUID>) {
            assertEquals(revision, value["revision"]!!.jsonPrimitive.long); assertEquals(count, value["goingCount"]!!.jsonPrimitive.int); assertEquals(status, value["status"]!!.jsonPrimitive.content)
            val id = UUID.fromString(value.id()); val chat = UUID.fromString(value["chatId"]!!.jsonPrimitive.content)
            f.connection().use { c ->
                c.prepareStatement("SELECT revision,lifecycle,starts_at::text,ends_at::text FROM meeting_proposals WHERE id=? AND chat_id=?").use { s ->
                    s.setObject(1, id); s.setObject(2, chat); s.executeQuery().use { rows ->
                        assertTrue(rows.next()); assertEquals(revision, rows.getLong(1)); assertEquals(if (status == "closed") "CLOSED" else "OPEN", rows.getString(2))
                    }
                }
                c.prepareStatement("SELECT user_id FROM votes WHERE proposal_id=?").use { s -> s.setObject(1, id); s.executeQuery().use { rows ->
                    assertEquals(voters, buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) })
                } }
                c.prepareStatement("SELECT id,booked_by_user_id,booking_order,status,cancel_reason FROM bookings WHERE proposal_id=? ORDER BY booking_order DESC").use { s ->
                    s.setObject(1, id); s.executeQuery().use { rows ->
                        if (value["booking"] == JsonNull) assertFalse(rows.next()) else {
                            assertTrue(rows.next()); val b = value["booking"]!!.jsonObject
                            assertEquals(b["id"]!!.jsonPrimitive.content, rows.getObject(1).toString()); assertEquals(b["bookedByUserId"]!!.jsonPrimitive.content, rows.getObject(2).toString())
                            assertEquals(b["bookingOrder"]!!.jsonPrimitive.long, rows.getLong(3)); assertEquals(b["status"]!!.jsonPrimitive.content.uppercase(), rows.getString(4))
                            assertEquals(b["cancelReason"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.uppercase(), rows.getString(5))
                        }
                    }
                }
            }
            for (member in f.members) {
                val expected = JsonObject(value.toMutableMap().apply { put("viewerGoing", JsonPrimitive(member in voters)) })
                assertEquals(expected, get(member, "/api/v1/proposals/$id"))
                val listed = get(member, "/api/v1/chats/$chat/proposals")["items"]!!.jsonArray.single { it.jsonObject["id"] == value["id"] }
                assertEquals(expected, listed)
            }
        }

        private fun connect(actor: UUID, chats: List<UUID>, accepted: Boolean = true): Pair<Wire, WebSocket> {
            val wire = Wire()
            val socket = http.newWebSocketBuilder().header("Authorization", f.bearer(actor)).buildAsync(URI(wsOrigin), wire).get(5, TimeUnit.SECONDS)
            sockets += socket
            assertEquals("hello", wire.next()["type"]!!.jsonPrimitive.content)
            val requestId = UUID.randomUUID()
            socket.sendText(buildJsonObject { put("v", 1); put("type", "subscribe"); put("requestId", requestId.toString()); put("chatIds", JsonArray(chats.map { JsonPrimitive(it.toString()) })) }.toString(), true).get(5, TimeUnit.SECONDS)
            val response = wire.next()
            assertEquals(requestId.toString(), response["requestId"]!!.jsonPrimitive.content)
            assertEquals(chats.map { JsonPrimitive(it.toString()) }, response[if (accepted) "acceptedChatIds" else "rejectedChatIds"]!!.jsonArray.toList())
            assertTrue(response[if (accepted) "rejectedChatIds" else "acceptedChatIds"]!!.jsonArray.isEmpty())
            return wire to socket
        }
        private suspend fun collect(actor: UUID, wire: Wire): List<JsonObject> {
            val marker = UUID.randomUUID()
            // Test-only ordered USER sentinel proves frame boundaries; it is not business evidence.
            hub.publishCommitted(RealtimeEvent(marker, RealtimeEventType.NOTIFICATION_CREATED, RealtimeScopeKind.USER, actor, Instant.now(), buildJsonObject { put("marker", marker.toString()) }))
            return wire.through(marker)
        }
        private suspend fun silence() {
            assertTrue(collect(f.author, authorWire).isEmpty()); voterWire?.let { assertTrue(collect(f.voter, it).isEmpty()) }
            assertTrue(collect(f.observer, observerWire).isEmpty()); assertTrue(collect(f.outsider, outsiderWire).isEmpty())
        }
        private suspend fun events(value: JsonObject, systemKinds: List<String> = emptyList(), bookingChanged: Boolean = false): List<JsonObject> {
            val actual = collect(f.author, authorWire)
            voterWire?.let { assertEquals(actual, collect(f.voter, it)) }
            val adjacent = collect(f.observer, observerWire)
            if (value["chatId"]!!.jsonPrimitive.content == f.chats[1].toString()) assertEquals(actual, adjacent) else assertTrue(adjacent.isEmpty())
            assertTrue(collect(f.outsider, outsiderWire).isEmpty())
            assertEquals(1 + systemKinds.size + if (bookingChanged) 1 else 0, actual.size)
            val booking = value["booking"]?.takeUnless { it == JsonNull }?.jsonObject
            val update = actual.single { it["type"]!!.jsonPrimitive.content == "proposal.updated" }
            assertEquals(buildJsonObject { put("proposalId", value["id"]!!); put("revision", value["revision"]!!); put("status", value["status"]!!); put("goingCount", value["goingCount"]!!); put("bookingId", booking?.get("id") ?: JsonNull) }, update["payload"])
            val messages = actual.filter { it["type"]!!.jsonPrimitive.content == "chat.message.created" }.map { it["payload"]!!.jsonObject }
            assertEquals(systemKinds, messages.map { it["system"]!!.jsonObject["kind"]!!.jsonPrimitive.content })
            for (message in messages) f.connection().use { c -> c.prepareStatement("SELECT message_seq,system_payload,created_at FROM messages WHERE id=? AND chat_id=?").use { s ->
                s.setObject(1, UUID.fromString(message["id"]!!.jsonPrimitive.content)); s.setObject(2, UUID.fromString(value["chatId"]!!.jsonPrimitive.content)); s.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(message["messageSeq"]!!.jsonPrimitive.long, rows.getLong(1)); assertEquals(message["system"], Json.parseToJsonElement(rows.getString(2)))
                    assertEquals("system", message["type"]!!.jsonPrimitive.content); assertEquals(JsonNull, message["author"]); assertEquals(JsonNull, message["text"])
                }
            } }
            if (bookingChanged) assertEquals(buildJsonObject { put("proposalId", value["id"]!!); put("bookingId", booking!!["id"]!!); put("status", booking["status"]!!); put("cancelReason", booking["cancelReason"]!!) }, actual.single { it["type"]!!.jsonPrimitive.content == "booking.changed" }["payload"])
            for (event in actual) {
                assertEquals(buildJsonObject { put("kind", "chat"); put("id", value["chatId"]!!) }, event["scope"])
                UUID.fromString(event["eventId"]!!.jsonPrimitive.content); assertFalse(event.toString().contains("viewerGoing"))
            }
            ledger[ledger.lastIndex] = JsonObject(ledger.last().toMutableMap().apply { put("committedEvents", JsonArray(actual)) })
            return actual
        }
        private fun bookingRow(id: String): JsonObject = f.connection().use { c -> c.prepareStatement("SELECT row_to_json(b)::text FROM bookings b WHERE id=?").use { s ->
            s.setObject(1, UUID.fromString(id)); s.executeQuery().use { rows -> check(rows.next()); Json.parseToJsonElement(rows.getString(1)).jsonObject }
        } }
        private fun dbProof(): JsonObject = f.connection().use { c -> c.prepareStatement("""SELECT jsonb_build_object(
            'proposals',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY p.id),'[]'::jsonb) FROM meeting_proposals p WHERE chat_id IN (?,?)),
            'votes',(SELECT coalesce(jsonb_agg(to_jsonb(v) ORDER BY v.proposal_id,v.user_id),'[]'::jsonb) FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id IN (?,?)),
            'bookings',(SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY b.booking_order),'[]'::jsonb) FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id IN (?,?)),
            'chatSequences',(SELECT jsonb_agg(jsonb_build_object('chatId',id,'seq',last_message_seq) ORDER BY id) FROM chats WHERE id IN (?,?)),
            'systemCount',(SELECT count(*) FROM messages WHERE chat_id IN (?,?)),
            'notificationCount',(SELECT count(*) FROM notification_events WHERE point_id=?),
            'outcomes',(SELECT coalesce(jsonb_agg(jsonb_build_object('key',idempotency_key,'type',operation_type,'outcome',outcome,'resultRef',result_ref) ORDER BY idempotency_key),'[]'::jsonb) FROM operation_idempotency WHERE actor_user_id IN (?,?,?,?)))::text""").use { s ->
                (f.chats + f.chats + f.chats + f.chats + f.chats + listOf(f.point) + f.people).forEachIndexed { index, id -> s.setObject(index + 1, id) }
                s.executeQuery().use { rows -> check(rows.next()); Json.parseToJsonElement(rows.getString(1)).jsonObject }
            } }
        override fun close() { sockets.forEach { it.abort() } }
    }

    private class Wire : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<JsonObject>()
        private val buffer = StringBuilder()
        val closed = CompletableFuture<Int>()
        override fun onOpen(socket: WebSocket) { socket.request(1) }
        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data); if (last) { frames.add(Json.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }
            socket.request(1); return CompletableFuture.completedFuture(null)
        }
        override fun onClose(socket: WebSocket, code: Int, reason: String): CompletionStage<*> { closed.complete(code); return CompletableFuture.completedFuture(null) }
        override fun onError(socket: WebSocket, error: Throwable) { closed.completeExceptionally(error) }
        fun next(): JsonObject = assertNotNull(frames.poll(5, TimeUnit.SECONDS), "Expected native WS frame")
        fun through(id: UUID): List<JsonObject> {
            val result = mutableListOf<JsonObject>(); val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                assertTrue(System.nanoTime() < deadline, "Bounded WS sentinel deadline")
                val frame = next(); if (frame["eventId"]?.jsonPrimitive?.content == id.toString()) return result
                result += frame
            }
        }
    }
}