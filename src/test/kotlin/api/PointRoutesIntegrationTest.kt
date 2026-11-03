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
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PointRoutesIntegrationTest {
    @Test
    fun `create replay map and last leave use one stored point lifecycle`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step4routesfinal$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?, 'test-hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, actor); statement.setString(2, phone); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
                .use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
        }
        val secret = "point-route-integration-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        try {
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == session)
                        UserSelf(actor.toString(), "Test", null, phone, 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions,
                    pointReadRepository = PointReadRepository(runtime, secret),
                    pointWriteService = PointWriteService(runtime))
            }
            val now = Instant.now()
            val token = "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            val key = UUID.randomUUID().toString()
            val request = """{"position":{"lat":55.75,"lon":37.62},"topic":{"presetKey":"football","customLabel":null},"address":"Test","addressSource":"manual","confirmNearby":true}"""
            suspend fun create() = client.post("/api/v1/points") {
                header(HttpHeaders.Authorization, token)
                header("Idempotency-Key", key)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val first = create()
            assertEquals(HttpStatusCode.Created, first.status)
            val firstBody = first.bodyAsText()
            val point = Json.parseToJsonElement(firstBody).jsonObject["point"]!!.jsonObject
            val response = Json.parseToJsonElement(firstBody).jsonObject
            assertEquals(setOf("point", "chat", "quota"), response.keys)
            assertEquals("1", response["quota"]!!.jsonObject["weekUsed"]!!.jsonPrimitive.content)
            assertEquals("1", response["chat"]!!.jsonObject["participantCount"]!!.jsonPrimitive.content)
            val pointId = point["id"]!!.jsonPrimitive.content
            val chatId = Json.parseToJsonElement(firstBody).jsonObject["chat"]!!.jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.Created, create().status)
            assertEquals(Json.parseToJsonElement(firstBody), Json.parseToJsonElement(create().bodyAsText()))

            val map = client.get("/api/v1/points?west=37&south=55&east=38&north=56")
            assertEquals(HttpStatusCode.OK, map.status)
            val publicPoint = Json.parseToJsonElement(map.bodyAsText()).jsonObject["items"]!!.jsonArray
                .single { it.jsonObject["id"]!!.jsonPrimitive.content == pointId }.jsonObject
            assertEquals(setOf("id", "position", "address", "addressSource", "peopleCount", "topics", "createdAt", "updatedAt"),
                publicPoint.keys)
            assertEquals(setOf("id", "presetKey", "label", "participantCount", "chatId"),
                publicPoint["topics"]!!.jsonArray.single().jsonObject.keys)
            val detail = client.get("/api/v1/points/$pointId")
            assertEquals(HttpStatusCode.OK, detail.status)
            assertEquals(publicPoint, Json.parseToJsonElement(detail.bodyAsText()).jsonObject)

            val leave = client.delete("/api/v1/chats/$chatId/membership") {
                header(HttpHeaders.Authorization, token)
            }
            assertEquals(HttpStatusCode.NoContent, leave.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/points/$pointId").status)
        } finally {
            runtime.close()
        }
    }
}
