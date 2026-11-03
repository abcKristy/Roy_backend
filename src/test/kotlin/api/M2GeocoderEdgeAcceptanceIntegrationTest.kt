package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.geocoding.DaDataReverseGeocoder
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.PointWriteService
import com.sun.net.httpserver.HttpServer
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M2GeocoderEdgeAcceptanceIntegrationTest {
    @Test
    fun `provider null and timeout leave DB untouched before validated manual create`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_geocoder_edges$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection -> seedUser(connection, actor) }
        val mode = AtomicReference("none")
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        provider.createContext("/reverse") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                if (mode.get() == "slow") Thread.sleep(1000)
                val bytes = """{"suggestions":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) {
                exchange.close()
            }
        }
        provider.start()
        try {
            val secret = "m2-geocoder-edge-secret-at-least-32-bytes"
            HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
                appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
                val sessions = SessionAccessService(secret, object : SessionRepository {
                    override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                        if (identity.userId == actor && identity.sessionId == session)
                            UserSelf(actor.toString(), "Geocoder actor", null, "+12025550980", 20, null, now.toString())
                        else null
                    override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
                })
                application {
                    configureTransport()
                    configureRouting(runtime, sessionAccess = sessions,
                        pointWriteService = PointWriteService(runtime), reverseGeocoder = DaDataReverseGeocoder(
                            "test-only-key", URI("http://127.0.0.1:${provider.address.port}/reverse"),
                            requestTimeout = Duration.ofMillis(200)))
                }
                val now = Instant.now()
                val auth = "Bearer " + JwtTokenIssuer(secret).issue(actor, session, 0,
                    now.minusSeconds(10), now.plusSeconds(3600))
                suspend fun reverse() = client.post("/api/v1/geocoding/reverse") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"position":{"lat":-45,"lon":-80}}""")
                }
                suspend fun create(address: String) = client.post("/api/v1/points") {
                    header(HttpHeaders.Authorization, auth)
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"position":{"lat":-45,"lon":-80},"topic":{"presetKey":"football"},"address":"$address","addressSource":"manual","confirmNearby":true}""")
                }
                val none = reverse()
                assertEquals(HttpStatusCode.OK, none.status)
                val noneBody = Json.parseToJsonElement(none.bodyAsText()).jsonObject
                assertEquals("none", noneBody["source"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, noneBody.getValue("address"))
                mode.set("slow")
                val timeout = reverse()
                assertEquals(HttpStatusCode.ServiceUnavailable, timeout.status)
                assertEquals("GEOCODER_UNAVAILABLE", Json.parseToJsonElement(timeout.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                val blank = create(" ")
                assertEquals(HttpStatusCode.UnprocessableEntity, blank.status)
                assertEquals("INVALID_FIELD", Json.parseToJsonElement(blank.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                DriverManager.getConnection(url, user, password).use { connection ->
                    assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", actor))
                    assertEquals(0, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
                    assertEquals(0, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", actor))
                }
                val manual = create("Manual after timeout")
                assertEquals(HttpStatusCode.Created, manual.status)
                val manualBody = Json.parseToJsonElement(manual.bodyAsText()).jsonObject
                val point = manualBody["point"]!!.jsonObject
                assertEquals("manual", point["addressSource"]!!.jsonPrimitive.content)
                val pointId = UUID.fromString(point["id"]!!.jsonPrimitive.content)
                val chatId = UUID.fromString(manualBody["chat"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                DriverManager.getConnection(url, user, password).use { connection ->
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM points WHERE id=? AND created_by_user_id=? AND address_source='MANUAL'", pointId, actor))
                    assertEquals(1, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", pointId))
                    assertEquals(1, count(connection,
                        """SELECT count(*) FROM chats c JOIN point_topics t ON t.id=c.point_topic_id
                           WHERE c.id=? AND t.point_id=? AND c.participant_count=1""", chatId, pointId))
                    assertEquals(1, count(connection, "SELECT count(*) FROM point_creation_events WHERE point_id=?", pointId))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL", chatId, actor))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=? AND result_ref=? AND outcome='SUCCESS'",
                        actor, pointId))
                }
            }
        } finally {
            provider.stop(0)
        }
    }

    private fun seedUser(connection: Connection, actor: UUID) {
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'synthetic-unusable-hash','Geocoder actor',20,'fixture-only',
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
