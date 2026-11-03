package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.PointWriteService
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M2MonthlyQuotaBoundaryIntegrationTest {
    @Test
    fun `month nine to ten then refusal replay block and expiry recovery persist correctly`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_quota_edges$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'synthetic-unusable-hash','Month actor',20,'fixture-only',
                           clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, phone)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
                .use { statement -> statement.setObject(1, actor); assertEquals(1, statement.executeUpdate()) }
            repeat(9) {
                connection.prepareStatement(
                    "INSERT INTO point_creation_events (id,creator_user_id,created_at) VALUES (?,?,clock_timestamp()-interval '8 days')"
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, actor)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            connection.commit()
        }
        val secret = "m2-month-boundary-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == session)
                        UserSelf(actor.toString(), "Month actor", null, "+12025550980", 20, null, now.toString())
                    else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions, pointWriteService = PointWriteService(runtime))
            }
            val now = Instant.now()
            val token = "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            suspend fun create(key: UUID, lat: Double) = client.post("/api/v1/points") {
                header(HttpHeaders.Authorization, token)
                header("Idempotency-Key", key.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"position":{"lat":$lat,"lon":40.0},"topic":{"presetKey":"football"},"address":"Month $lat","addressSource":"manual","confirmNearby":true}""")
            }
            val tenthKey = UUID.randomUUID()
            val tenth = create(tenthKey, -20.0)
            assertEquals(HttpStatusCode.Created, tenth.status)
            val tenthBody = Json.parseToJsonElement(tenth.bodyAsText()).jsonObject
            assertEquals("10", tenthBody["quota"]!!.jsonObject["monthUsed"]!!.jsonPrimitive.content)
            assertEquals("0", tenthBody["quota"]!!.jsonObject["monthRemaining"]!!.jsonPrimitive.content)
            val tenthPoint = UUID.fromString(tenthBody["point"]!!.jsonObject["id"]!!.jsonPrimitive.content)

            val refusalKey = UUID.randomUUID()
            val refused = create(refusalKey, -20.02)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            val refusalBody = Json.parseToJsonElement(refused.bodyAsText()).jsonObject
            val error = refusalBody["error"]!!.jsonObject
            assertEquals("POINT_QUOTA_EXCEEDED", error["code"]!!.jsonPrimitive.content)
            val blockedUntil = Instant.parse(error["details"]!!.jsonObject["blockedUntil"]!!.jsonPrimitive.content)
            val replay = create(refusalKey, -20.02)
            assertEquals(HttpStatusCode.Conflict, replay.status)
            assertEquals(refusalBody, Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
            val blockedKey = UUID.randomUUID()
            val blocked = create(blockedKey, -20.04)
            assertEquals(HttpStatusCode.Conflict, blocked.status)
            assertEquals("POINT_QUOTA_BLOCKED", Json.parseToJsonElement(blocked.bodyAsText())
                .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(10, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
                assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", actor))
                assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id=?", tenthPoint))
                assertEquals(2, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", actor))
                connection.prepareStatement(
                    """SELECT u.create_point_blocked_until,i.created_at,i.outcome,i.response_snapshot
                       FROM users u JOIN operation_idempotency i ON i.actor_user_id=u.id
                       WHERE u.id=? AND i.idempotency_key=?::text"""
                ).use { statement ->
                    statement.setObject(1, actor)
                    statement.setObject(2, refusalKey)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(blockedUntil, rows.getObject(1, OffsetDateTime::class.java).toInstant())
                        assertEquals(rows.getObject(2, OffsetDateTime::class.java).toInstant()
                            .plus(720, ChronoUnit.HOURS), blockedUntil)
                        assertEquals("QUOTA_EXCEEDED", rows.getString(3))
                        val saved = Json.parseToJsonElement(rows.getString(4)).jsonObject
                        assertEquals("409", saved["status"]!!.jsonPrimitive.content)
                        assertEquals(refusalBody, saved["body"]!!.jsonObject)
                    }
                }
            }

            // Move only this synthetic actor's old events outside the 30-day window and
            // expire the actor block. This avoids waiting a real month or changing server time.
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.autoCommit = false
                connection.prepareStatement(
                    "UPDATE point_creation_events SET created_at=clock_timestamp()-interval '31 days' WHERE creator_user_id=?"
                ).use { statement -> statement.setObject(1, actor); assertEquals(10, statement.executeUpdate()) }
                connection.prepareStatement(
                    "UPDATE users SET create_point_blocked_until=clock_timestamp()-interval '1 second' WHERE id=?"
                ).use { statement -> statement.setObject(1, actor); assertEquals(1, statement.executeUpdate()) }
                connection.commit()
            }
            val recovered = create(UUID.randomUUID(), -20.06)
            assertEquals(HttpStatusCode.Created, recovered.status)
            val recoveredBody = Json.parseToJsonElement(recovered.bodyAsText()).jsonObject
            assertEquals("1", recoveredBody["quota"]!!.jsonObject["monthUsed"]!!.jsonPrimitive.content)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(11, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
                assertEquals(2, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", actor))
                assertEquals(3, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", actor))
            }
        }
    }

    private fun count(connection: Connection, sql: String, actor: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, actor)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
