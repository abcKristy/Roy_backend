package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.geocoding.ReverseGeocodeResult
import com.example.geocoding.ReverseGeocoder
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.CreatePointCommand
import com.example.points.CreatePointResult
import com.example.points.PointAddressSource
import com.example.points.PointPosition
import com.example.points.PointTopicInput
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M2CreateGeocoderAcceptanceIntegrationTest {
    @Test
    fun `provider result and unavailable fallback create separate complete graphs and replay exactly`() = testApplication {
        val db = guardedDatabase()
        val providerActor = UUID.randomUUID()
        val manualActor = UUID.randomUUID()
        val providerSession = UUID.randomUUID()
        val manualSession = UUID.randomUUID()
        DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, providerActor)
            seedUser(connection, manualActor)
            connection.commit()
        }
        val secret = "m2-create-acceptance-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(db.url, db.user, db.password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == providerActor && identity.sessionId == providerSession)
                        UserSelf(providerActor.toString(), "Provider", null, "+12025550981", 20, null, now.toString())
                    else if (identity.userId == manualActor && identity.sessionId == manualSession)
                        UserSelf(manualActor.toString(), "Manual", null, "+12025550982", 20, null, now.toString())
                    else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            var geocoderResult: ReverseGeocodeResult = ReverseGeocodeResult.Found("Provider address")
            var geocoderCalls = 0
            val geocoder = ReverseGeocoder { geocoderCalls++; geocoderResult }
            application {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions,
                    pointWriteService = PointWriteService(runtime), reverseGeocoder = geocoder)
            }
            val now = Instant.now()
            fun token(actor: UUID, session: UUID) = "Bearer " + JwtTokenIssuer(secret).issue(
                actor, session, 0, now.minusSeconds(10), now.plusSeconds(3600))
            val providerToken = token(providerActor, providerSession)
            val manualToken = token(manualActor, manualSession)
            suspend fun reverse(auth: String, lat: Double, lon: Double) = client.post("/api/v1/geocoding/reverse") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"position":{"lat":$lat,"lon":$lon}}""")
            }
            suspend fun create(auth: String, key: UUID, lat: Double, lon: Double, address: String,
                               source: String) = client.post("/api/v1/points") {
                header(HttpHeaders.Authorization, auth)
                header("Idempotency-Key", key.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"position":{"lat":$lat,"lon":$lon},"topic":{"presetKey":"football"},"address":"$address","addressSource":"$source","confirmNearby":true}""")
            }

            val found = reverse(providerToken, -41.0, -71.0)
            assertEquals(HttpStatusCode.OK, found.status)
            val foundBody = Json.parseToJsonElement(found.bodyAsText()).jsonObject
            assertEquals("Provider address", foundBody["address"]!!.jsonPrimitive.content)
            assertEquals("provider", foundBody["source"]!!.jsonPrimitive.content)
            val providerKey = UUID.randomUUID()
            val providerCreated = create(providerToken, providerKey, -41.0, -71.0,
                foundBody["address"]!!.jsonPrimitive.content, foundBody["source"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.Created, providerCreated.status)
            val providerBody = Json.parseToJsonElement(providerCreated.bodyAsText()).jsonObject
            assertEquals("provider", providerBody["point"]!!.jsonObject["addressSource"]!!.jsonPrimitive.content)
            assertEquals("Provider address", providerBody["point"]!!.jsonObject["address"]!!.jsonPrimitive.content)

            geocoderResult = ReverseGeocodeResult.Unavailable
            val unavailable = reverse(manualToken, -41.01, -71.01)
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            assertEquals("GEOCODER_UNAVAILABLE", Json.parseToJsonElement(unavailable.bodyAsText())
                .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            val manualKey = UUID.randomUUID()
            val manualCreated = create(manualToken, manualKey, -41.01, -71.01,
                "Manual address", "manual")
            assertEquals(HttpStatusCode.Created, manualCreated.status)
            assertEquals(2, geocoderCalls)
            val manualBody = Json.parseToJsonElement(manualCreated.bodyAsText()).jsonObject
            assertEquals("manual", manualBody["point"]!!.jsonObject["addressSource"]!!.jsonPrimitive.content)
            assertEquals("Manual address", manualBody["point"]!!.jsonObject["address"]!!.jsonPrimitive.content)

            val replay = create(manualToken, manualKey, -41.01, -71.01, "Manual address", "manual")
            assertEquals(HttpStatusCode.Created, replay.status)
            assertEquals(manualBody, Json.parseToJsonElement(replay.bodyAsText()).jsonObject)
            val reusedKey = create(manualToken, manualKey, -41.01, -71.01, "Changed address", "manual")
            assertEquals(HttpStatusCode.Conflict, reusedKey.status)
            assertEquals("IDEMPOTENCY_KEY_REUSED", Json.parseToJsonElement(reusedKey.bodyAsText())
                .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

            for ((actor, body, expectedSource, expectedAddress) in listOf(
                AcceptanceCase(providerActor, providerBody, "GEOCODED", "Provider address"),
                AcceptanceCase(manualActor, manualBody, "MANUAL", "Manual address"),
            )) {
                val point = body["point"]!!.jsonObject
                val chat = body["chat"]!!.jsonObject
                val pointId = UUID.fromString(point["id"]!!.jsonPrimitive.content)
                val chatId = UUID.fromString(chat["id"]!!.jsonPrimitive.content)
                DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
                    connection.prepareStatement(
                        """SELECT p.address,p.address_source,t.preset_key,c.id,c.participant_count,m.user_id,m.left_at
                           FROM points p JOIN point_topics t ON t.point_id=p.id
                           JOIN chats c ON c.point_topic_id=t.id
                           JOIN chat_memberships m ON m.chat_id=c.id
                           WHERE p.id=? AND p.created_by_user_id=?"""
                    ).use { statement ->
                        statement.setObject(1, pointId)
                        statement.setObject(2, actor)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(expectedAddress, rows.getString(1))
                            assertEquals(expectedSource, rows.getString(2))
                            assertEquals("football", rows.getString(3))
                            assertEquals(chatId, rows.getObject(4, UUID::class.java))
                            assertEquals(1, rows.getInt(5))
                            assertEquals(actor, rows.getObject(6, UUID::class.java))
                            assertEquals(null, rows.getObject(7))
                            assertTrue(!rows.next())
                        }
                    }
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM point_creation_events WHERE creator_user_id=? AND point_id=?", actor, pointId))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=? AND result_ref=? AND outcome='SUCCESS'",
                        actor, pointId))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM points WHERE created_by_user_id=?", actor))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM point_topics WHERE point_id=?", pointId))
                    assertEquals(1, count(connection,
                        """SELECT count(*) FROM chats c JOIN point_topics t ON t.id=c.point_topic_id
                           WHERE t.point_id=?""", pointId))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND left_at IS NULL", chatId))
                }
            }
        }
    }

    @Test
    fun `failed create projection rolls back point topic chat member ledger and key then same key can retry`() = testApplication {
        val db = guardedDatabase()
        val actor = UUID.randomUUID()
        DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
            seedUser(connection, actor)
        }
        HikariDatabaseBootstrap(ServerConfig(db.url, db.user, db.password,
            "m2-create-rollback-secret-at-least-32-bytes", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val key = UUID.randomUUID()
            val command = CreatePointCommand(actor, key, PointPosition(-42.0, -72.0),
                PointTopicInput("football"), "Rollback address", PointAddressSource.MANUAL, true)
            var projectionInvoked = false
            val failure = assertFailsWith<IllegalStateException> {
                service.create(command, { connection, created ->
                    projectionInvoked = true
                    assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id=?", created.pointId))
                    assertEquals(1, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", created.pointId))
                    assertEquals(1, count(connection, "SELECT count(*) FROM chats WHERE id=?", created.chatId))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=?", created.chatId, actor))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM point_creation_events WHERE creator_user_id=? AND point_id=?",
                        actor, created.pointId))
                    error("forced response projection failure")
                },
                    { buildJsonObject { put("blockedUntil", it.toString()) } })
            }
            assertTrue(projectionInvoked)
            assertEquals("forced response projection failure", failure.message)
            DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
                assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", actor))
                assertEquals(0, count(connection, "SELECT count(*) FROM chat_memberships WHERE user_id=?", actor))
                assertEquals(0, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
                assertEquals(0, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", actor))
                assertEquals(0, count(connection,
                    """SELECT count(*) FROM point_topics t JOIN points p ON p.id=t.point_id
                       WHERE p.created_by_user_id=?""", actor))
                assertEquals(0, count(connection,
                    """SELECT count(*) FROM chats c JOIN point_topics t ON t.id=c.point_topic_id
                       JOIN points p ON p.id=t.point_id WHERE p.created_by_user_id=?""", actor))
            }
            val created = assertIs<CreatePointResult.Created>(service.create(command,
                PointWriteSnapshot::create, { buildJsonObject { put("blockedUntil", it.toString()) } }))
            DriverManager.getConnection(db.url, db.user, db.password).use { connection ->
                assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id=?", created.value.pointId))
                assertEquals(1, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
            }
        }
    }

    private data class Database(val url: String, val user: String, val password: String)
    private data class AcceptanceCase(val actor: UUID, val body: kotlinx.serialization.json.JsonObject,
                                      val source: String, val address: String)

    private fun guardedDatabase(): Database {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_create$").matches(url))
        return Database(url, System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required"),
            System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required"))
    }

    private fun seedUser(connection: Connection, actor: UUID) {
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'synthetic-unusable-hash','M2 create actor',20,'fixture-only',
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
