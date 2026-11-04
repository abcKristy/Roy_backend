package com.example.acceptance

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

class ChatAcceptanceFixtureIntegrationTest {
    @Test
    fun `synthetic namespace reproduces access and sixteen reports and cleans only owned data`() = testApplication {
        val url = com.example.testing.testDatabaseUrl()
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "fixture-acceptance-secret-at-least-32-bytes"
        ChatAcceptanceFixture(url, user, password).use { sentinel ->
            ChatAcceptanceFixture(url, user, password).use { fixture ->
                assertTrue(sentinel.people.toSet().intersect(fixture.people.toSet()).isEmpty())
                assertEquals(19, fixture.count("users")); assertEquals(2, fixture.count("points"))
                assertEquals(2, fixture.count("chats")); assertEquals(4, fixture.count("messages"))
                HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.PRODUCTION)).start().use { db ->
                    application { configureTransport(); configureRouting(db, sessionAccess = SessionAccessService(secret, JdbcSessionRepository(db)), chatReadService = com.example.chats.ChatReadService(db, secret), moderationService = com.example.moderation.ModerationService(db)) }
                    suspend fun history(actor: UUID, chat: UUID) = client.get("/api/v1/chats/$chat/messages") {
                        header(HttpHeaders.Authorization, fixture.bearer(actor, secret))
                    }
                    val visible = history(fixture.member, fixture.chats[0])
                    assertEquals(HttpStatusCode.OK, visible.status)
                    val items = Json.parseToJsonElement(visible.bodyAsText()).jsonObject.getValue("items").jsonArray
                    assertEquals(listOf(2L, 1L), items.map { it.jsonObject.getValue("messageSeq").jsonPrimitive.long })
                    assertEquals(listOf("system", "text"), items.map { it.jsonObject.getValue("type").jsonPrimitive.content })
                    assertMembershipSystemMessage(items[0].jsonObject, fixture.points[0], fixture.chats[0], fixture.peer)
                    assertEquals(fixture.messages[1].toString(), items[0].jsonObject.getValue("id").jsonPrimitive.content)
                    val second = history(fixture.peer, fixture.chats[1])
                    assertEquals(HttpStatusCode.OK, second.status)
                    val secondSystem = Json.parseToJsonElement(second.bodyAsText()).jsonObject.getValue("items").jsonArray[0].jsonObject
                    assertMembershipSystemMessage(secondSystem, fixture.points[1], fixture.chats[1], fixture.peer)
                    assertEquals(fixture.messages[3].toString(), secondSystem.getValue("id").jsonPrimitive.content)
                    for ((actor, chat) in listOf(fixture.outsider to fixture.chats[0], fixture.member to fixture.chats[1])) {
                        repeat(2) {
                            val refused = history(actor, chat)
                            assertEquals(HttpStatusCode.Forbidden, refused.status)
                            assertFalse(refused.bodyAsText().contains("Synthetic history"))
                        }
                    }
                    for ((index, reporter) in fixture.reporters.withIndex()) {
                        val key = UUID.randomUUID().toString()
                        val body = buildJsonObject { put("contextChatId", fixture.chats[0].toString()); put("targetUserId", fixture.peer.toString()); put("reasonText", "Synthetic fixture reason") }.toString()
                        suspend fun report() = client.post("/api/v1/reports") {
                            header(HttpHeaders.Authorization, fixture.bearer(reporter, secret)); header("Idempotency-Key", key)
                            contentType(ContentType.Application.Json); setBody(body)
                        }
                        val response = report(); assertEquals(HttpStatusCode.Created, response.status)
                        val canonical = Json.parseToJsonElement(response.bodyAsText())
                        val replay = report(); assertEquals(HttpStatusCode.Created, replay.status)
                        assertEquals(canonical, Json.parseToJsonElement(replay.bodyAsText()))
                        assertEquals(index + 1, fixture.count("reports"))
                        fixture.connection().use { c -> c.prepareStatement("SELECT report_mute_triggered_at,message_muted_until FROM users WHERE id=?").use { s ->
                            s.setObject(1, fixture.peer); s.executeQuery().use { r ->
                                assertTrue(r.next())
                                val triggered = r.getObject(1, OffsetDateTime::class.java)?.toInstant()
                                val until = r.getObject(2, OffsetDateTime::class.java)?.toInstant()
                                if (index < 15) { assertNull(triggered); assertNull(until) }
                                else { assertEquals(assertNotNull(triggered).plusSeconds(604800), until) }
                            }
                        } }
                    }
                }
                fixture.close(); fixture.close()
                for (table in listOf("users", "points", "chats", "messages", "reports")) assertEquals(0, fixture.count(table))
                assertEquals(19, sentinel.count("users")); assertEquals(2, sentinel.count("points"))
                assertEquals(4, sentinel.count("messages")); assertEquals(0, sentinel.count("reports"))
            }
        }
    }
}
