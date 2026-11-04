package com.example.meetings

import com.example.api.configureTransport
import com.example.api.meetingRoutes
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcIdempotencyOperations
import com.example.testing.testDatabaseUrl
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProposalTimeIntegrationTest {
    @Test
    fun `server clock enforces strict future and interval bounds and UTC retries`() = testApplication {
        val url = testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m4_time$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-proposal-time-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        val actor = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        fun connection() = DriverManager.getConnection(url, user, password)
        try {
            connection().use { c ->
                c.autoCommit = false
                val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'synthetic-test-hash','Time fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", actor, phone)
                execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                    VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic time fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, actor)
                execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, actor)
                c.commit()
            }
            val now = Instant.parse("2030-04-12T15:30:00Z")
            val clock = MutableClock(now)
            val proposals = ProposalService(runtime, secret, clock = clock)
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor) UserSelf(actor.toString(), "Synthetic", null,
                        "+79990000000", 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                routing { meetingRoutes(proposals, BookingService(runtime), sessions) }
            }
            val authNow = Instant.now()
            val bearer = "Bearer " + JwtTokenIssuer(secret).issue(actor, UUID.randomUUID(), 0,
                authNow.minusSeconds(10), authNow.plusSeconds(3600))
            suspend fun create(start: String, end: String, key: UUID = UUID.randomUUID()) =
                client.post("/api/v1/chats/$chat/proposals") {
                    header(HttpHeaders.Authorization, bearer)
                    header("Idempotency-Key", key.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"startsAt":"$start","endsAt":"$end"}""")
                }
            val future = now.plusSeconds(3600)
            val invalid = listOf(
                now.minusNanos(1_000).toString() to future.toString(),
                now.toString() to future.toString(),
                now.plusNanos(1).toString() to future.toString(),
                future.toString() to future.toString(),
                future.toString() to future.minusNanos(1_000).toString(),
                future.plusNanos(1).toString() to future.plusNanos(2).toString(),
                "not-an-instant" to future.toString(),
            )
            for ((start, end) in invalid) {
                val response = create(start, end)
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("INVALID_FIELD", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            }
            connection().use { c ->
                for (table in listOf("meeting_proposals", "messages")) {
                    c.prepareStatement("SELECT count(*) FROM $table WHERE chat_id=?").use { s ->
                        s.setObject(1, chat)
                        s.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                    }
                }
            }
            // One microsecond is the smallest PostgreSQL timestamptz precision.
            val starts = now.plusNanos(1_000)
            val ends = starts.plusSeconds(5400)
            val key = UUID.randomUUID()
            val startOffset = starts.plusNanos(123).atOffset(ZoneOffset.ofHours(3)).toString()
            val endOffset = ends.plusNanos(456).atOffset(ZoneOffset.ofHours(3)).toString()
            val created = create(startOffset, endOffset, key)
            assertEquals(HttpStatusCode.Created, created.status)
            val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals(starts.toString(), createdBody["startsAt"]!!.jsonPrimitive.content)
            assertEquals(ends.toString(), createdBody["endsAt"]!!.jsonPrimitive.content)
            assertEquals(now.toString(), createdBody["createdAt"]!!.jsonPrimitive.content)
            val proposalId = UUID.fromString(createdBody["id"]!!.jsonPrimitive.content)
            // Replaying a completed operation after its start is safe and returns its old snapshot.
            clock.set(ends.plusSeconds(1))
            val replay = create(starts.plusNanos(123).toString(), ends.plusNanos(456).toString(), key)
            assertEquals(HttpStatusCode.Created, replay.status)
            assertEquals(createdBody, Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
            val changedFraction = create(starts.toString(), ends.toString(), key)
            assertEquals(HttpStatusCode.Conflict, changedFraction.status)
            assertEquals("IDEMPOTENCY_KEY_REUSED", Json.parseToJsonElement(changedFraction.bodyAsText())
                .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            val freshLateAttempt = create(startOffset, endOffset)
            assertEquals(HttpStatusCode.UnprocessableEntity, freshLateAttempt.status)
            connection().use { c ->
                c.prepareStatement("SELECT starts_at,ends_at,created_at FROM meeting_proposals WHERE id=?").use { s ->
                    s.setObject(1, proposalId)
                    s.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(starts, rows.getObject(1, OffsetDateTime::class.java).toInstant())
                        assertEquals(ends, rows.getObject(2, OffsetDateTime::class.java).toInstant())
                        assertEquals(now, rows.getObject(3, OffsetDateTime::class.java).toInstant())
                    }
                }
                for (table in listOf("meeting_proposals", "messages")) {
                    c.prepareStatement("SELECT count(*) FROM $table WHERE chat_id=?").use { s ->
                        s.setObject(1, chat)
                        s.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
                    }
                }
                c.prepareStatement("SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?").use { s ->
                    s.setObject(1, actor)
                    s.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
                }
                c.prepareStatement("SELECT system_payload->>'startsAt',system_payload->>'endsAt' FROM messages WHERE chat_id=?").use { s ->
                    s.setObject(1, chat)
                    s.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(starts.toString(), rows.getString(1))
                        assertEquals(ends.toString(), rows.getString(2))
                    }
                }
            }
            // Legacy requests hashed full instants; PostgreSQL rounded this interval to 1µs.
            // Truncation collapses it, so replay must precede the new persisted-bound check.
            val legacyId = UUID.randomUUID()
            val legacyKey = UUID.randomUUID()
            val legacyStart = future.plusNanos(100)
            val legacyEnd = future.plusNanos(900)
            val legacyCommand = CreateProposalCommand(actor, chat, legacyKey, legacyStart, legacyEnd)
            val legacySnapshot = buildJsonObject {
                put("status", 201)
                put("body", buildJsonObject {
                    put("id", legacyId.toString())
                    put("startsAt", future.toString())
                    put("endsAt", future.plusNanos(1_000).toString())
                })
            }
            connection().use { c ->
                c.autoCommit = false
                execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,clock_timestamp(),clock_timestamp())""", legacyId, chat, actor,
                    legacyStart.atOffset(ZoneOffset.UTC), legacyEnd.atOffset(ZoneOffset.UTC))
                val oldHash = JdbcIdempotencyOperations.requestHash("create_proposal", listOf(chat), buildJsonObject {
                    put("startsAt", legacyStart.toString())
                    put("endsAt", legacyEnd.toString())
                })
                val idempotency = JdbcIdempotencyOperations()
                assertEquals(JdbcIdempotencyOperations.Decision.New,
                    idempotency.lockActorAndCheck(c, actor, "create_proposal", legacyKey, oldHash))
                idempotency.recordSuccess(c, actor, "create_proposal", legacyKey, oldHash, legacyId, legacySnapshot)
                c.commit()
            }
            assertEquals(legacySnapshot, assertIs<CreateProposalResult.Replay>(
                proposals.create(legacyCommand) { _, _ -> error("Legacy replay must not execute success callback") }
            ).responseSnapshot)
            assertEquals(CreateProposalResult.KeyReused,
                proposals.create(legacyCommand.copy(startsAt = legacyStart.plusNanos(1))) { _, _ ->
                    error("Changed full-precision request must not execute success callback")
                })
            connection().use { c ->
                c.prepareStatement("SELECT starts_at,ends_at FROM meeting_proposals WHERE id=?").use { s ->
                    s.setObject(1, legacyId)
                    s.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(future, rows.getObject(1, OffsetDateTime::class.java).toInstant())
                        assertEquals(future.plusNanos(1_000), rows.getObject(2, OffsetDateTime::class.java).toInstant())
                    }
                }
                for ((table, column, id, expected) in listOf(
                    CountCheck("meeting_proposals", "chat_id", chat, 2),
                    CountCheck("messages", "chat_id", chat, 1),
                    CountCheck("operation_idempotency", "actor_user_id", actor, 2),
                )) {
                    c.prepareStatement("SELECT count(*) FROM $table WHERE $column=?").use { s ->
                        s.setObject(1, id)
                        s.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(expected, rows.getInt(1)) }
                    }
                }
            }
        } finally {
            connection().use { c ->
                c.autoCommit = false
                execute(c, "DELETE FROM points WHERE id=?", point)
                execute(c, "DELETE FROM users WHERE id=?", actor)
                c.commit()
            }
            runtime.close()
        }
    }

    private class MutableClock(initial: Instant) : Clock() {
        private val current = AtomicReference(initial)
        fun set(value: Instant) { current.set(value) }
        override fun instant(): Instant = current.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    private data class CountCheck(val table: String, val column: String, val id: UUID, val expected: Int)

    private fun execute(c: Connection, sql: String, vararg values: Any) {
        c.prepareStatement(sql).use { s ->
            values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
            s.executeUpdate()
        }
    }
}
