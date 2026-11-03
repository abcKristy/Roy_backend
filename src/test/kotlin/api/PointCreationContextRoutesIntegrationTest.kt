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
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointCreationContextRoutesIntegrationTest {
    @Test
    fun `creation context is authenticated read only and post rechecks nearby`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t2_nearby$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val nearbyPoint = UUID.randomUUID()
        val lat = 10.0 + Math.random() * 10.0
        val lon = 110.0 + Math.random() * 10.0
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'test-hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, phone)
                assertEquals(1, statement.executeUpdate())
            }
            repeat(2) { index ->
                connection.prepareStatement(
                    "INSERT INTO point_creation_events (id,creator_user_id,created_at) VALUES (?,?,?)"
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, actor)
                    statement.setObject(3, Instant.now().minusSeconds(3600L * (index + 1)).atOffset(ZoneOffset.UTC))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            connection.commit()
        }

        val secret = "creation-context-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == session)
                        UserSelf(actor.toString(), "Tester", null, phone, 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions,
                    pointReadRepository = PointReadRepository(runtime, secret),
                    pointWriteService = PointWriteService(runtime))
            }
            val now = Instant.now()
            val bearer = "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            val contextPath = "/api/v1/points/creation-context?lat=$lat&lon=$lon"
            assertEquals(HttpStatusCode.Unauthorized, client.get(contextPath).status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me/point-quota").status)

            val invalid = client.get("$contextPath&lat=90") { header(HttpHeaders.Authorization, bearer) }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("INVALID_BBOX"))
            assertEquals(HttpStatusCode.BadRequest, client.get(
                "/api/v1/points/creation-context?lat=91&lon=$lon"
            ) { header(HttpHeaders.Authorization, bearer) }.status)

            val context = client.get(contextPath) { header(HttpHeaders.Authorization, bearer) }
            assertEquals(HttpStatusCode.OK, context.status)
            val body = Json.parseToJsonElement(context.bodyAsText()).jsonObject
            assertEquals(setOf("nearby", "quota"), body.keys)
            assertTrue(body["nearby"]!!.jsonArray.isEmpty())
            val quota = body["quota"]!!.jsonObject
            assertEquals("2", quota["weekUsed"]!!.jsonPrimitive.content)
            assertEquals("1", quota["weekRemaining"]!!.jsonPrimitive.content)
            assertEquals("2", quota["monthUsed"]!!.jsonPrimitive.content)
            assertEquals("true", quota["lastAvailableWarning"]!!.jsonPrimitive.content)
            val directQuota = client.get("/api/v1/me/point-quota") { header(HttpHeaders.Authorization, bearer) }
            assertEquals(HttpStatusCode.OK, directQuota.status)
            assertEquals(quota, Json.parseToJsonElement(directQuota.bodyAsText()).jsonObject)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(2, countEvents(connection, actor))
                assertEquals(0, countPoints(connection, actor))
                connection.prepareStatement("SELECT create_point_blocked_until FROM users WHERE id=?").use { statement ->
                    statement.setObject(1, actor)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(null, rows.getObject(1))
                    }
                }
                connection.prepareStatement(
                    """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                       VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),'New nearby point','MANUAL',?,
                               clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, nearbyPoint)
                    statement.setDouble(2, lon)
                    statement.setDouble(3, lat)
                    statement.setObject(4, actor)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            val refreshed = client.get(contextPath) { header(HttpHeaders.Authorization, bearer) }
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val preview = Json.parseToJsonElement(refreshed.bodyAsText()).jsonObject["nearby"]!!.jsonArray.single().jsonObject
            assertEquals(nearbyPoint.toString(), preview["id"]!!.jsonPrimitive.content)
            assertEquals("0", preview["distanceMeters"]!!.jsonPrimitive.content)
            assertEquals("New nearby point", preview["address"]!!.jsonPrimitive.content)

            val create = client.post("/api/v1/points") {
                header(HttpHeaders.Authorization, bearer)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"position":{"lat":$lat,"lon":$lon},"topic":{"presetKey":"football"},
                   "address":"Test","addressSource":"manual","confirmNearby":false}""")
            }
            assertEquals(HttpStatusCode.Conflict, create.status)
            assertTrue(create.bodyAsText().contains("NEARBY_CONFIRM_REQUIRED"))
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(2, countEvents(connection, actor))
                assertEquals(1, countPoints(connection, actor))
            }
        }
    }

    private fun countEvents(connection: Connection, actor: UUID): Int = connection.prepareStatement(
        "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?"
    ).use { statement ->
        statement.setObject(1, actor)
        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }

    private fun countPoints(connection: Connection, actor: UUID): Int = connection.prepareStatement(
        "SELECT count(*) FROM points WHERE created_by_user_id=?"
    ).use { statement ->
        statement.setObject(1, actor)
        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }
}
