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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M2LostTcpCreateResponseIntegrationTest {
    @Test
    fun `client drops TCP response after commit and same key returns saved create body`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL is required for M2 TCP acceptance")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_tcp_replay$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val key = UUID.randomUUID()
        val address = "Lost TCP $actor"
        val body = """{"position":{"lat":-48.0,"lon":-75.0},"topic":{"presetKey":"football"},"address":"$address","addressSource":"manual","confirmNearby":true}"""
        DriverManager.getConnection(url, user, password).use { connection -> seedUser(connection, actor) }
        val secret = "m2-lost-tcp-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId == actor && identity.sessionId == session)
                        UserSelf(actor.toString(), "TCP actor", null, "+12025550980", 20, null, now.toString())
                    else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            // Reserve and release a loopback port before Netty binds it. A competing
            // process could take it in between; server.start then fails visibly.
            val port = ServerSocket(0).use { it.localPort }
            val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
                configureTransport()
                configureRouting(runtime, sessionAccess = sessions, pointWriteService = PointWriteService(runtime))
            }
            server.start(wait = false)
            try {
                val now = Instant.now()
                val token = JwtTokenIssuer(secret).issue(actor, session, 0,
                    now.minusSeconds(10), now.plusSeconds(3600))
                val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                val endpoint = URI("http://127.0.0.1:$port/api/v1/points")
                waitForReady(http, port)
                val bytes = body.toByteArray(Charsets.UTF_8)
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 3000
                    socket.setSoLinger(true, 0)
                    val headers = "POST /api/v1/points HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\n" +
                        "Authorization: Bearer $token\r\n" +
                        "Idempotency-Key: $key\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                    // The client never reads an HTTP status or body. Wait only for the DB commit,
                    // then close with RST to model loss of the complete response.
                    waitForCommittedKey(url, user, password, actor, key)
                }
                val retry = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer $token")
                    .header("Idempotency-Key", key.toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val response = http.send(retry, HttpResponse.BodyHandlers.ofString())
                assertEquals(201, response.statusCode())
                val replay = Json.parseToJsonElement(response.body()).jsonObject
                val pointId = UUID.fromString(replay["point"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                DriverManager.getConnection(url, user, password).use { connection ->
                    assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id=? AND created_by_user_id=?", pointId, actor))
                    assertEquals(1, count(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", pointId))
                    assertEquals(1, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=? AND point_id=?", actor, pointId))
                    assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", actor))
                    assertEquals(1, count(connection,
                        """SELECT count(*) FROM point_topics t JOIN points p ON p.id=t.point_id
                           WHERE p.created_by_user_id=?""", actor))
                    assertEquals(1, count(connection,
                        """SELECT count(*) FROM chats c JOIN point_topics t ON t.id=c.point_topic_id
                           JOIN points p ON p.id=t.point_id WHERE p.created_by_user_id=?""", actor))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM chat_memberships WHERE user_id=? AND left_at IS NULL", actor))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", actor))
                    assertEquals(1, count(connection,
                        "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=? AND operation_type='create_point'", actor))
                    connection.prepareStatement(
                        """SELECT response_snapshot FROM operation_idempotency
                           WHERE actor_user_id=? AND operation_type='create_point' AND idempotency_key=?::text
                             AND result_ref=? AND outcome='SUCCESS'"""
                    ).use { statement ->
                        statement.setObject(1, actor)
                        statement.setObject(2, key)
                        statement.setObject(3, pointId)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            val saved = Json.parseToJsonElement(rows.getString(1)).jsonObject
                            assertEquals("201", saved["status"]!!.jsonPrimitive.content)
                            assertEquals(replay, saved["body"]!!.jsonObject)
                            assertTrue(!rows.next())
                        }
                    }
                }
            } finally {
                server.stop(100, 1000)
            }
        }
    }

    private fun waitForReady(http: HttpClient, port: Int) {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health"))
            .timeout(Duration.ofSeconds(1)).GET().build()
        repeat(100) {
            try {
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return
            } catch (_: java.io.IOException) { }
            Thread.sleep(25)
        }
        error("Loopback server did not become ready")
    }

    private fun waitForCommittedKey(url: String, user: String, password: String, actor: UUID, key: UUID) {
        repeat(200) {
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=? AND idempotency_key=?::text"
                ).use { statement ->
                    statement.setObject(1, actor)
                    statement.setObject(2, key)
                    statement.executeQuery().use { rows -> if (rows.next() && rows.getInt(1) == 1) return }
                }
            }
            Thread.sleep(25)
        }
        error("Create did not commit before response loss")
    }

    private fun seedUser(connection: Connection, actor: UUID) {
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'synthetic-unusable-hash','TCP actor',20,'fixture-only',
                       clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, actor); statement.setString(2, phone); assertEquals(1, statement.executeUpdate()) }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, actor); assertEquals(1, statement.executeUpdate()) }
    }

    private fun count(connection: Connection, sql: String, vararg ids: UUID): Int = connection.prepareStatement(sql).use { statement ->
        ids.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
