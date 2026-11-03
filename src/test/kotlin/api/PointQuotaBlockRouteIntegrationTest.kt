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
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PointQuotaBlockRouteIntegrationTest {
    @Test
    fun `lost quota response replays committed 409 without extending block`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t2_guards$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'test-hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0'))
                assertEquals(1, statement.executeUpdate())
            }
            repeat(3) {
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
        val secret = "point-quota-route-test-secret-with-32-characters"
        val sessions = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                if (identity.userId == actor && identity.sessionId == session)
                    UserSelf(actor.toString(), "Tester", null, "+79990000000", 20, null, now.toString()) else null
            override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
        })
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        runtime.use { database ->
            application {
                configureTransport()
                configureRouting(database, sessionAccess = sessions, pointWriteService = PointWriteService(database))
            }
            val now = Instant.now()
            val token = JwtTokenIssuer(secret).issue(actor, session, 0, now.minusSeconds(10), now.plusSeconds(3600))
            val key = UUID.randomUUID()
            val payload = """{"position":{"lat":55.75,"lon":37.62},"topic":{"presetKey":"football"},"address":"Quota probe","addressSource":"manual","confirmNearby":false}"""
            suspend fun send(idempotencyKey: UUID) = client.post("/api/v1/points") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Idempotency-Key", idempotencyKey.toString())
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            val first = send(key)
            assertEquals(HttpStatusCode.Conflict, first.status)
            val firstBody = Json.parseToJsonElement(first.bodyAsText()).jsonObject
            assertEquals("POINT_QUOTA_EXCEEDED", firstBody["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
            val blockedUntil = assertNotNull(firstBody["error"]?.jsonObject?.get("details")?.jsonObject
                ?.get("blockedUntil")?.jsonPrimitive?.content)

            // Simulate a lost response: the same key must replay the stored status and body.
            val replay = send(key)
            assertEquals(HttpStatusCode.Conflict, replay.status)
            assertEquals(firstBody, Json.parseToJsonElement(replay.bodyAsText()).jsonObject)

            val later = send(UUID.randomUUID())
            assertEquals(HttpStatusCode.Conflict, later.status)
            val laterError = Json.parseToJsonElement(later.bodyAsText()).jsonObject["error"]!!.jsonObject
            assertEquals("POINT_QUOTA_BLOCKED", laterError["code"]?.jsonPrimitive?.content)
            assertEquals(blockedUntil, laterError["details"]?.jsonObject?.get("blockedUntil")?.jsonPrimitive?.content)

            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    """SELECT u.create_point_blocked_until, i.created_at, i.outcome, i.response_snapshot,
                              (SELECT count(*) FROM operation_idempotency WHERE actor_user_id = u.id),
                              (SELECT count(*) FROM points WHERE created_by_user_id = u.id)
                       FROM users u JOIN operation_idempotency i ON i.actor_user_id = u.id
                       WHERE u.id = ? AND i.operation_type = 'create_point' AND i.idempotency_key = ?"""
                ).use { statement ->
                    statement.setObject(1, actor)
                    statement.setString(2, key.toString())
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        val persistedBlock = rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant()
                        val attemptedAt = rows.getObject(2, java.time.OffsetDateTime::class.java).toInstant()
                        assertEquals(Instant.parse(blockedUntil), persistedBlock)
                        assertEquals(attemptedAt.plus(30, ChronoUnit.DAYS), persistedBlock)
                        assertEquals("QUOTA_EXCEEDED", rows.getString(3))
                        val snapshot = Json.parseToJsonElement(rows.getString(4)).jsonObject
                        assertEquals("409", snapshot["status"]?.jsonPrimitive?.content)
                        assertEquals(firstBody, snapshot["body"]?.jsonObject)
                        assertEquals(1, rows.getInt(5))
                        assertEquals(0, rows.getInt(6))
                        assertTrue(!rows.next())
                    }
                }
            }
        }
    }
}
