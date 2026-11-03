package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.PointReadRepository
import com.example.points.PointWriteService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T055: observable HTTP refusals and their committed database effects. */
class M2QuotaTopicFilterAcceptanceIntegrationTest {
    @Test
    fun `last slot survives point deletion and quota refusal replays 720 hour block`() = testApplication {
        val db = guardedDatabase()
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actor)
            repeat(2) {
                connection.prepareStatement(
                    "INSERT INTO point_creation_events (id,creator_user_id,created_at) VALUES (?,?,clock_timestamp() - interval '1 day')"
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, actor)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            connection.commit()
        }
        val secret = "m2-quota-acceptance-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(db.url, db.user, db.password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions(secret, mapOf(actor to session)),
                    pointWriteService = PointWriteService(runtime))
            }
            val auth = token(secret, actor, session)
            val payload = createPayload(-54.0, 111.0)
            suspend fun create(key: UUID) = client.post("/api/v1/points") {
                header(HttpHeaders.Authorization, auth)
                header("Idempotency-Key", key.toString())
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val lastSlot = create(UUID.randomUUID())
            assertEquals(HttpStatusCode.Created, lastSlot.status)
            val created = body(lastSlot.bodyAsText())
            val pointId = UUID.fromString(created.getValue("point").jsonObject.getValue("id").jsonPrimitive.content)
            val chatId = UUID.fromString(created.getValue("chat").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("0", created.getValue("quota").jsonObject.getValue("weekRemaining").jsonPrimitive.content)

            val leave = client.delete("/api/v1/chats/$chatId/membership") {
                header(HttpHeaders.Authorization, auth)
            }
            assertEquals(HttpStatusCode.NoContent, leave.status)
            DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
                assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE id=?", pointId))
                assertEquals(3, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
                assertEquals(1, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE creator_user_id=? AND point_id IS NULL AND created_at > clock_timestamp() - interval '1 hour'", actor))
            }

            val blockedKey = UUID.randomUUID()
            val rejected = create(blockedKey)
            assertEquals(HttpStatusCode.Conflict, rejected.status)
            val rejectedBody = body(rejected.bodyAsText())
            assertEquals("POINT_QUOTA_EXCEEDED", errorCode(rejectedBody))
            val until = Instant.parse(rejectedBody.getValue("error").jsonObject.getValue("details")
                .jsonObject.getValue("blockedUntil").jsonPrimitive.content)
            val replay = create(blockedKey)
            assertEquals(HttpStatusCode.Conflict, replay.status)
            assertEquals(rejectedBody, body(replay.bodyAsText()))
            val later = create(UUID.randomUUID())
            assertEquals(HttpStatusCode.Conflict, later.status)
            assertEquals("POINT_QUOTA_BLOCKED", errorCode(body(later.bodyAsText())))
            DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
                connection.prepareStatement(
                    """SELECT u.create_point_blocked_until,i.created_at,i.outcome,i.response_snapshot,
                              (SELECT count(*) FROM operation_idempotency WHERE actor_user_id=u.id AND operation_type='create_point'),
                              (SELECT count(*) FROM points WHERE created_by_user_id=u.id)
                       FROM users u JOIN operation_idempotency i ON i.actor_user_id=u.id
                       WHERE u.id=? AND i.idempotency_key=? AND i.operation_type='create_point'"""
                ).use { statement ->
                    statement.setObject(1, actor)
                    statement.setString(2, blockedKey.toString())
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(until, rows.getObject(1, OffsetDateTime::class.java).toInstant())
                        assertEquals(rows.getObject(2, OffsetDateTime::class.java).toInstant().plus(720, ChronoUnit.HOURS), until)
                        assertEquals("QUOTA_EXCEEDED", rows.getString(3))
                        val snapshot = body(rows.getString(4))
                        assertEquals("409", snapshot.getValue("status").jsonPrimitive.content)
                        assertEquals(rejectedBody, snapshot.getValue("body").jsonObject)
                        assertEquals(2, rows.getInt(5)) // success and one terminal refusal; blocked retry is not recorded
                        assertEquals(0, rows.getInt(6))
                        assertTrue(!rows.next())
                    }
                }
                assertEquals(3, count(connection,
                    "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
            }
        }
    }

    @Test
    fun `duplicate topic and changed cursor filters refuse without mutating point graph`() = testApplication {
        val db = guardedDatabase()
        val owner = UUID.randomUUID()
        val guest = UUID.randomUUID()
        val sessionsByUser = mapOf(owner to UUID.randomUUID(), guest to UUID.randomUUID())
        DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, owner)
            seedUser(connection, guest)
            connection.commit()
        }
        val secret = "m2-topic-filter-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(db.url, db.user, db.password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions(secret, sessionsByUser),
                    pointWriteService = PointWriteService(runtime),
                    pointReadRepository = PointReadRepository(runtime, secret))
            }
            val ownerAuth = token(secret, owner, sessionsByUser.getValue(owner))
            val guestAuth = token(secret, guest, sessionsByUser.getValue(guest))
            // The label isolates map pages even when this class runs repeatedly on the same database.
            val label = "case-${UUID.randomUUID().toString().take(30)}"
            val lat = ThreadLocalRandom.current().nextDouble(-70.0, -60.0)
            val lon = ThreadLocalRandom.current().nextDouble(80.0, 90.0)
            suspend fun create(latitude: Double) = client.post("/api/v1/points") {
                header(HttpHeaders.Authorization, ownerAuth)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody(createPayload(latitude, lon))
            }
            val first = create(lat)
            val second = create(lat + 0.001)
            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            val firstId = UUID.fromString(body(first.bodyAsText()).getValue("point").jsonObject.getValue("id").jsonPrimitive.content)
            val secondId = UUID.fromString(body(second.bodyAsText()).getValue("point").jsonObject.getValue("id").jsonPrimitive.content)
            suspend fun add(pointId: UUID, topicLabel: String, key: UUID) = client.post("/api/v1/points/$pointId/topics") {
                header(HttpHeaders.Authorization, ownerAuth)
                header("Idempotency-Key", key.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"topic":{"presetKey":"other","customLabel":"$topicLabel"}}""")
            }
            val firstTopic = add(firstId, label, UUID.randomUUID())
            val secondTopic = add(secondId, label, UUID.randomUUID())
            assertEquals(HttpStatusCode.Created, firstTopic.status)
            assertEquals(HttpStatusCode.Created, secondTopic.status)
            val firstChat = UUID.fromString(body(firstTopic.bodyAsText()).getValue("chat").jsonObject.getValue("id").jsonPrimitive.content)
            val duplicateKey = UUID.randomUUID()
            val duplicate = add(firstId, "  ${label.uppercase()}  ", duplicateKey)
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals("DUPLICATE_TOPIC", errorCode(body(duplicate.bodyAsText())))
            val join = client.put("/api/v1/chats/$firstChat/membership") {
                header(HttpHeaders.Authorization, guestAuth)
            }
            assertEquals(HttpStatusCode.OK, join.status)
            val detail = client.get("/api/v1/points/$firstId")
            assertEquals(HttpStatusCode.OK, detail.status)
            val detailBody = body(detail.bodyAsText())
            assertEquals("2", detailBody.getValue("peopleCount").jsonPrimitive.content)
            assertEquals("2", detailBody.getValue("topics").jsonArray.single {
                it.jsonObject.getValue("presetKey").jsonPrimitive.content == "other"
            }.jsonObject.getValue("participantCount").jsonPrimitive.content)

            val bbox = "west=${lon - 0.01}&south=${lat - 0.01}&east=${lon + 0.01}&north=${lat + 0.01}"
            val query = "$bbox&topicKey=other&customTopicLabel=$label&peopleMin=1&peopleMax=2&limit=1"
            val page = client.get("/api/v1/points?$query")
            assertEquals(HttpStatusCode.OK, page.status)
            val cursor = assertNotNull(body(page.bodyAsText()).getValue("nextCursor").jsonPrimitive.content)
            val next = client.get("/api/v1/points?$query&cursor=$cursor")
            assertEquals(HttpStatusCode.OK, next.status)
            assertEquals(JsonNull, body(next.bodyAsText()).getValue("nextCursor"))
            val changed = client.get("/api/v1/points?$bbox&topicKey=other&customTopicLabel=$label&peopleMin=2&peopleMax=2&limit=1&cursor=$cursor")
            assertEquals(HttpStatusCode.BadRequest, changed.status)
            assertEquals("INVALID_CURSOR", errorCode(body(changed.bodyAsText())))
            val invalid = client.get("/api/v1/points?$bbox&topicKey=other&customTopicLabel=$label&peopleMin=3&peopleMax=2")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("INVALID_FIELD", errorCode(body(invalid.bodyAsText())))
            DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
                assertEquals(2, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", firstId))
                assertEquals(2, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", secondId))
                assertEquals(0, count(connection,
                    "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=? AND idempotency_key=?::text", owner, duplicateKey))
                assertEquals(2, count(connection,
                    """SELECT count(DISTINCT m.user_id) FROM chat_memberships m JOIN chats c ON c.id=m.chat_id
                       JOIN point_topics t ON t.id=c.point_topic_id WHERE t.point_id=? AND m.left_at IS NULL""", firstId))
                assertEquals(2, count(connection,
                    "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND left_at IS NULL", firstChat))
            }
        }
    }

    private data class Database(val url: String, val user: String, val password: String)

    private fun guardedDatabase(): Database {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_quota_topic_filter$").matches(url))
        return Database(url, System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required"),
            System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required"))
    }

    private fun sessions(secret: String, active: Map<UUID, UUID>) = SessionAccessService(secret,
        object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                if (active[identity.userId] == identity.sessionId)
                    UserSelf(identity.userId.toString(), "T055 actor", null, "+12025550985", 20, null, now.toString())
                else null
            override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
        })

    private fun token(secret: String, user: UUID, session: UUID): String {
        val now = Instant.now()
        return "Bearer " + JwtTokenIssuer(secret).issue(user, session, 0,
            now.minusSeconds(10), now.plusSeconds(3600))
    }

    private fun createPayload(lat: Double, lon: Double) =
        """{"position":{"lat":$lat,"lon":$lon},"topic":{"presetKey":"football"},"address":"T055 synthetic","addressSource":"manual","confirmNearby":true}"""

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun errorCode(body: JsonObject) = body.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private fun seedUser(connection: Connection, actor: UUID) {
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'synthetic-unusable-hash','T055 actor',20,'fixture-only',
                       clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, actor)
            statement.setString(2, phone)
            assertEquals(1, statement.executeUpdate())
        }
        connection.prepareStatement(
            "INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())"
        ).use { statement ->
            statement.setObject(1, actor)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun count(connection: Connection, sql: String, vararg ids: UUID): Int =
        connection.prepareStatement(sql).use { statement ->
            ids.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
}
