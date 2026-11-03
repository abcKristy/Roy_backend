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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M2AtomicCreateFailureIntegrationTest {
    @Test
    fun `each graph insert failure rolls back HTTP create and same key retry commits once`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL is required for M2 atomic acceptance")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_atomic_failures$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val tables = listOf("point_topics", "chats", "chat_memberships", "point_creation_events")
        val actors = tables.associateWith { UUID.randomUUID() }
        val sessionsByActor = actors.values.associateWith { UUID.randomUUID() }
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            actors.values.forEach { actor -> seedUser(connection, actor) }
            connection.commit()
        }
        val secret = "m2-atomic-failure-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (sessionsByActor[identity.userId] == identity.sessionId)
                        UserSelf(identity.userId.toString(), "Atomic actor", null, "+12025550980", 20, null, now.toString())
                    else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions,
                    pointReadRepository = PointReadRepository(runtime, secret),
                    pointWriteService = PointWriteService(runtime))
            }
            val now = Instant.now()
            fun token(actor: UUID): String = "Bearer " + JwtTokenIssuer(secret).issue(
                actor, sessionsByActor.getValue(actor), 0, now.minusSeconds(10), now.plusSeconds(3600))

            try {
                installFunction(url, user, password, tables)
                for (table in tables) {
                    val actor = actors.getValue(table)
                    val key = UUID.randomUUID()
                    val lat = ThreadLocalRandom.current().nextDouble(-55.0, -50.0)
                    val lon = ThreadLocalRandom.current().nextDouble(-76.0, -70.0)
                    val address = "M2 atomic $actor"
                    val payload = """{"position":{"lat":$lat,"lon":$lon},"topic":{"presetKey":"football"},"address":"$address","addressSource":"manual","confirmNearby":true}"""
                    suspend fun create() = client.post("/api/v1/points") {
                        header(HttpHeaders.Authorization, token(actor))
                        header("Idempotency-Key", key.toString())
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                    val before = snapshot(url, user, password)
                    try {
                        installTrigger(url, user, password, table)
                        val failed = create()
                        assertEquals(HttpStatusCode.InternalServerError, failed.status, table)
                        val body = failed.bodyAsText()
                        val error = Json.parseToJsonElement(body).jsonObject
                        assertEquals("INTERNAL_ERROR", error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                        assertTrue(error["requestId"]!!.jsonPrimitive.content.isNotBlank())
                        assertFalse(body.contains("M2_ATOMIC_INJECTED_FAILURE"))
                        assertFalse(body.contains(table))
                        assertFalse(body.contains(password))
                        assertFalse(body.contains(url))
                        assertEquals(before, snapshot(url, user, password), "No committed row after $table failure")
                        DriverManager.getConnection(url, user, password).use { connection ->
                            assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", actor))
                            assertEquals(0, count(connection, "SELECT count(*) FROM chat_memberships WHERE user_id=?", actor))
                            assertEquals(0, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
                            assertEquals(0, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", actor))
                        }
                        val map = client.get("/api/v1/points?west=${lon - 0.00001}&south=${lat - 0.00001}&east=${lon + 0.00001}&north=${lat + 0.00001}")
                        assertEquals(HttpStatusCode.OK, map.status)
                        assertTrue(Json.parseToJsonElement(map.bodyAsText()).jsonObject["items"]!!.jsonArray.isEmpty())
                    } finally {
                        dropTrigger(url, user, password, table)
                    }
                    val retry = create()
                    assertEquals(HttpStatusCode.Created, retry.status, table)
                    val created = Json.parseToJsonElement(retry.bodyAsText()).jsonObject
                    assertEquals("1", created["quota"]!!.jsonObject["weekUsed"]!!.jsonPrimitive.content)
                    val pointId = UUID.fromString(created["point"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                    val chatId = UUID.fromString(created["chat"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                    DriverManager.getConnection(url, user, password).use { connection ->
                        assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id=? AND created_by_user_id=?", pointId, actor))
                        assertEquals(1, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", pointId))
                        assertEquals(1, count(connection, "SELECT count(*) FROM chats WHERE id=? AND participant_count=1", chatId))
                        assertEquals(1, count(connection, "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL", chatId, actor))
                        assertEquals(1, count(connection, "SELECT count(*) FROM point_creation_events WHERE point_id=? AND creator_user_id=?", pointId, actor))
                        assertEquals(1, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=? AND result_ref=? AND outcome='SUCCESS'", actor, pointId))
                        connection.prepareStatement(
                            """SELECT response_snapshot FROM operation_idempotency
                               WHERE actor_user_id=? AND operation_type='create_point'
                                 AND idempotency_key=?::text AND result_ref=? AND outcome='SUCCESS'"""
                        ).use { statement ->
                            statement.setObject(1, actor)
                            statement.setObject(2, key)
                            statement.setObject(3, pointId)
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                val saved = Json.parseToJsonElement(rows.getString(1)).jsonObject
                                assertEquals("201", saved["status"]!!.jsonPrimitive.content)
                                assertEquals(created, saved["body"]!!.jsonObject)
                                assertFalse(rows.next())
                            }
                        }
                    }
                    val detail = client.get("/api/v1/points/$pointId")
                    assertEquals(HttpStatusCode.OK, detail.status)
                    assertEquals(address, Json.parseToJsonElement(detail.bodyAsText()).jsonObject["address"]!!.jsonPrimitive.content)
                    val replay = create()
                    assertEquals(HttpStatusCode.Created, replay.status)
                    assertEquals(created, Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
                }
            } finally {
                cleanup(url, user, password, tables)
            }
        }
    }

    private fun installFunction(url: String, user: String, password: String, tables: List<String>) =
        DriverManager.getConnection(url, user, password).use { connection ->
            tables.forEach { table -> connection.createStatement().use {
                it.execute("DROP TRIGGER IF EXISTS m2_atomic_fail_before_insert ON $table")
            } }
            connection.createStatement().use { it.execute("DROP FUNCTION IF EXISTS m2_atomic_fail_probe()") }
            connection.createStatement().use { it.execute(
                """CREATE FUNCTION m2_atomic_fail_probe() RETURNS trigger LANGUAGE plpgsql AS
                   $$ BEGIN RAISE EXCEPTION 'M2_ATOMIC_INJECTED_FAILURE'; END $$"""
            ) }
        }

    private fun installTrigger(url: String, user: String, password: String, table: String) =
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute(
                "CREATE TRIGGER m2_atomic_fail_before_insert BEFORE INSERT ON $table " +
                    "FOR EACH ROW EXECUTE FUNCTION m2_atomic_fail_probe()"
            ) }
        }

    private fun dropTrigger(url: String, user: String, password: String, table: String) =
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("DROP TRIGGER IF EXISTS m2_atomic_fail_before_insert ON $table") }
        }

    private fun cleanup(url: String, user: String, password: String, tables: List<String>) =
        DriverManager.getConnection(url, user, password).use { connection ->
            tables.forEach { table -> connection.createStatement().use {
                it.execute("DROP TRIGGER IF EXISTS m2_atomic_fail_before_insert ON $table")
            } }
            connection.createStatement().use { it.execute("DROP FUNCTION IF EXISTS m2_atomic_fail_probe()") }
        }

    private fun snapshot(url: String, user: String, password: String): List<Int> =
        DriverManager.getConnection(url, user, password).use { connection ->
            listOf("points", "point_topics", "chats", "chat_memberships", "messages",
                "point_creation_events", "operation_idempotency").map { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                        assertTrue(rows.next()); rows.getInt(1)
                    }
                }
            }
        }

    private fun seedUser(connection: Connection, actor: UUID) {
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'synthetic-unusable-hash','Atomic actor',20,'fixture-only',
                       clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, actor)
            statement.setString(2, phone)
            assertEquals(1, statement.executeUpdate())
        }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, actor); assertEquals(1, statement.executeUpdate()) }
    }

    private fun count(connection: Connection, sql: String, vararg ids: UUID): Int = connection.prepareStatement(sql).use { statement ->
        ids.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
