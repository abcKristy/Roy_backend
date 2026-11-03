package com.example.realtime

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.PointWriteService
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointLifecycleRealtimeIntegrationTest {
    @Test
    fun `committed leaves revoke old chat and delete notifies former members`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t5_events$").matches(url))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actors = List(2) { UUID.randomUUID() }
        val sessions = List(2) { UUID.randomUUID() }
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            actors.forEach { seedUser(connection, it) }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(90,-30),4326),'Events fixture','MANUAL',?,
                           clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, point)
                statement.setObject(2, actors[0])
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())"
            ).use { statement -> statement.setObject(1, topic); statement.setObject(2, point); assertEquals(1, statement.executeUpdate()) }
            connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())")
                .use { statement -> statement.setObject(1, chat); statement.setObject(2, topic); assertEquals(1, statement.executeUpdate()) }
            connection.prepareStatement(
                "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())"
            ).use { statement ->
                statement.setObject(1, chat)
                statement.setObject(2, actors[0])
                assertEquals(1, statement.executeUpdate())
            }
            connection.commit()
        }

        val secret = "point-lifecycle-events-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val access = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? {
                    val index = actors.indexOf(identity.userId)
                    return if (index >= 0 && identity.sessionId == sessions[index])
                        UserSelf(identity.userId.toString(), "Events tester", null, phone(identity.userId),
                            20, null, now.toString()) else null
                }
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            val hub = RealtimeHub(runtime, access)
            application {
                configureTransport()
                installRealtimeTransport()
                configureRouting(runtime, sessionAccess = access, pointWriteService = PointWriteService(runtime),
                    realtimeHub = hub)
            }
            val now = Instant.now()
            val tokens = actors.indices.map { index ->
                "Bearer " + JwtTokenIssuer(secret).issue(actors[index], sessions[index], 0,
                    now.minusSeconds(10), now.plusSeconds(3600))
            }
            val wsClient = createClient { install(WebSockets) }
            wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, tokens[0]) }) {
                val creatorSocket = this
                assertEquals("hello", nextEvent()["type"]?.jsonPrimitive?.content)
                send(Frame.Text(subscribe(chat)))
                assertEquals(chat.toString(), nextEvent()["acceptedChatIds"]?.jsonArray?.single()?.jsonPrimitive?.content)
                wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, tokens[1]) }) {
                    assertEquals("hello", nextEvent()["type"]?.jsonPrimitive?.content)
                    send(Frame.Text(subscribe(chat)))
                    assertEquals(chat.toString(), nextEvent()["rejectedChatIds"]?.jsonArray?.single()?.jsonPrimitive?.content)
                    val joined = client.put("/api/v1/chats/$chat/membership") {
                        header(HttpHeaders.Authorization, tokens[1])
                    }
                    assertEquals(HttpStatusCode.OK, joined.status)
                    val joinEvent = nextEvent()
                    assertEquals("chat.membership.changed", joinEvent["type"]?.jsonPrimitive?.content)
                    assertEquals("joined", joinEvent["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
                    val creatorJoinEvent = creatorSocket.nextEvent()
                    assertEquals("chat.membership.changed", creatorJoinEvent["type"]?.jsonPrimitive?.content)
                    assertEquals("joined", creatorJoinEvent["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
                    send(Frame.Text(subscribe(chat)))
                    assertEquals(chat.toString(), nextEvent()["acceptedChatIds"]?.jsonArray?.single()?.jsonPrimitive?.content)

                    val firstLeave = client.delete("/api/v1/chats/$chat/membership") {
                        header(HttpHeaders.Authorization, tokens[1])
                    }
                    assertEquals(HttpStatusCode.NoContent, firstLeave.status)
                    val ownChange = nextEvent()
                    assertEquals("chat.membership.changed", ownChange["type"]?.jsonPrimitive?.content)
                    assertEquals("left", ownChange["payload"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
                    send(Frame.Text(subscribe(chat)))
                    val rejected = nextEvent()
                    assertEquals(chat.toString(), rejected["rejectedChatIds"]?.jsonArray?.single()?.jsonPrimitive?.content)

                    hub.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.CHAT_MESSAGE_CREATED,
                        RealtimeScopeKind.CHAT, chat, Instant.now(), kotlinx.serialization.json.buildJsonObject {}))
                    assertNull(withTimeoutOrNull(250) { incoming.receive() }, "Exited socket received old chat event")

                    val lastLeave = client.delete("/api/v1/chats/$chat/membership") {
                        header(HttpHeaders.Authorization, tokens[0])
                    }
                    assertEquals(HttpStatusCode.NoContent, lastLeave.status)
                    val deleted = nextEvent()
                    assertEquals("point.deleted", deleted["type"]?.jsonPrimitive?.content)
                    assertEquals(point.toString(), deleted["payload"]?.jsonObject?.get("pointId")?.jsonPrimitive?.content)
                    send(Frame.Text(subscribe(chat)))
                    assertEquals(chat.toString(), nextEvent()["rejectedChatIds"]?.jsonArray?.single()?.jsonPrimitive?.content)
                }
                // The creator's socket may have queued chat events from the first leave.
                var pointDeleted = false
                var inspected = 0
                while (!pointDeleted && inspected < 6) {
                    val event = withTimeoutOrNull(2_000) { nextEvent() } ?: break
                    inspected++
                    if (event["type"]?.jsonPrimitive?.content == "point.deleted") {
                        assertEquals(point.toString(), event["payload"]?.jsonObject?.get("pointId")?.jsonPrimitive?.content)
                        pointDeleted = true
                    }
                }
                assertTrue(pointDeleted, "Creator did not receive point.deleted after committed cascade")
                send(Frame.Text(subscribe(chat)))
                assertEquals(chat.toString(), nextEvent()["rejectedChatIds"]?.jsonArray?.single()?.jsonPrimitive?.content)
            }
            DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                connection.prepareStatement("SELECT count(*) FROM points WHERE id=?").use { statement ->
                    statement.setObject(1, point)
                    statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                }
            }
        }
    }

    @Test
    fun `rolled back join publishes no membership success`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t5_events$").matches(url))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val existing = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val joining = UUID.randomUUID()
        val sessions = List(2) { UUID.randomUUID() }
        val chat = UUID.fromString("40000000-0000-4000-8000-000000000001")
        DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.autoCommit = false
            seedUser(connection, joining)
            connection.commit()
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS test_reject_lifecycle_join ON chat_memberships")
                statement.execute("DROP FUNCTION IF EXISTS test_reject_lifecycle_join()")
                statement.execute("""CREATE FUNCTION test_reject_lifecycle_join() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.user_id = '$joining'::uuid THEN
                            RAISE EXCEPTION 'forced rollback' USING ERRCODE = 'P0001';
                        END IF;
                        RETURN NEW;
                    END
                    $$""")
                statement.execute("""CREATE TRIGGER test_reject_lifecycle_join BEFORE INSERT ON chat_memberships
                    FOR EACH ROW EXECUTE FUNCTION test_reject_lifecycle_join()""")
            }
            connection.commit()
        }
        try {
            val secret = "point-lifecycle-rollback-secret-at-least-32-bytes"
            HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
                appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
                val access = SessionAccessService(secret, object : SessionRepository {
                    override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? {
                        val index = listOf(existing, joining).indexOf(identity.userId)
                        return if (index >= 0 && identity.sessionId == sessions[index])
                            UserSelf(identity.userId.toString(), "Events tester", null, phone(identity.userId),
                                20, null, now.toString()) else null
                    }
                    override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
                })
                val hub = RealtimeHub(runtime, access)
                application {
                    configureTransport()
                    installRealtimeTransport()
                    configureRouting(runtime, sessionAccess = access, pointWriteService = PointWriteService(runtime),
                        realtimeHub = hub)
                }
                val now = Instant.now()
                val tokens = listOf(existing, joining).indices.map { index ->
                    "Bearer " + JwtTokenIssuer(secret).issue(listOf(existing, joining)[index], sessions[index], 0,
                        now.minusSeconds(10), now.plusSeconds(3600))
                }
                val before = DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                    connection.prepareStatement("SELECT participant_count,last_message_seq FROM chats WHERE id=?").use { statement ->
                        statement.setObject(1, chat)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) to rows.getLong(2) }
                    }
                }
                val wsClient = createClient { install(WebSockets) }
                wsClient.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, tokens[0]) }) {
                    assertEquals("hello", nextEvent()["type"]?.jsonPrimitive?.content)
                    send(Frame.Text(subscribe(chat)))
                    assertEquals(chat.toString(), nextEvent()["acceptedChatIds"]?.jsonArray?.single()?.jsonPrimitive?.content)
                    val failed = client.put("/api/v1/chats/$chat/membership") {
                        header(HttpHeaders.Authorization, tokens[1])
                    }
                    assertEquals(HttpStatusCode.InternalServerError, failed.status)
                    assertNull(withTimeoutOrNull(250) { incoming.receive() }, "Rollback published a membership event")
                }
                DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                    connection.prepareStatement("SELECT participant_count,last_message_seq FROM chats WHERE id=?").use { statement ->
                        statement.setObject(1, chat)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(before, rows.getInt(1) to rows.getLong(2))
                        }
                    }
                    connection.prepareStatement(
                        "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=?"
                    ).use { statement ->
                        statement.setObject(1, chat)
                        statement.setObject(2, joining)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
                    }
                }
            }
        } finally {
            DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS test_reject_lifecycle_join ON chat_memberships")
                    statement.execute("DROP FUNCTION IF EXISTS test_reject_lifecycle_join()")
                }
            }
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.nextEvent() =
        Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

    private fun subscribe(chat: UUID) =
        """{"v":1,"type":"subscribe","requestId":"${UUID.randomUUID()}","chatIds":["$chat"]}"""

    private fun phone(id: UUID) = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')

    private fun seedUser(connection: Connection, id: UUID) {
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Events tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone(id)); assertEquals(1, statement.executeUpdate()) }
    }
}
