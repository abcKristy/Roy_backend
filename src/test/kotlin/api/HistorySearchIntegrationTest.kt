package com.example.api

import com.example.auth.*
import com.example.chats.ChatReadService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class HistorySearchIntegrationTest {
    @Test
    fun `search binds cursor and snapshot while escaping patterns without advancing read`() = testApplication {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val member = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val left = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val message = UUID.randomUUID()
        val privateText = "Private history marker ${UUID.randomUUID()}"
        val identities = listOf(member, outsider, left).associateWith { SessionIdentity(it, UUID.randomUUID(), 0) }
        val active = identities.values.toMutableSet()
        DriverManager.getConnection(url, user, password).use { c ->
            c.autoCommit = false
            for (id in identities.keys) {
                c.prepareStatement("""INSERT INTO users
                    (id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'test-hash','Private author marker',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""").use {
                    it.setObject(1, id)
                    it.setString(2, "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0'))
                    it.executeUpdate()
                }
            }
            c.prepareStatement("""INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                VALUES (?,ST_SetSRID(ST_MakePoint(70,-20),4326),'Private address marker','MANUAL',?,clock_timestamp(),clock_timestamp())""").use {
                it.setObject(1, point); it.setObject(2, member); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())").use {
                it.setObject(1, topic); it.setObject(2, point); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO chats (id,point_topic_id,last_message_seq,created_at) VALUES (?,?,6,clock_timestamp())").use {
                it.setObject(1, chat); it.setObject(2, topic); it.executeUpdate()
            }
            for (id in listOf(member, left)) {
                c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())").use {
                    it.setObject(1, chat); it.setObject(2, id); it.executeUpdate()
                }
            }
            c.prepareStatement("UPDATE chat_memberships SET left_at=clock_timestamp() WHERE chat_id=? AND user_id=?").use {
                it.setObject(1, chat); it.setObject(2, left); it.executeUpdate()
            }
            val texts = listOf("needle one", "needle two", "needle three", "100%_literal\\word", "ordinary", "🦊🦊🦊")
            for ((index, text) in texts.withIndex()) {
                c.prepareStatement("INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at) VALUES (?,?,?,?,'TEXT',?,timestamptz '2026-01-01 00:00:00+00'+?*interval '1 second')").use {
                    it.setObject(1, UUID.randomUUID()); it.setObject(2, chat); it.setInt(3, index+1); it.setObject(4, member); it.setString(5, text); it.setInt(6, index+1); it.executeUpdate()
                }
            }
            c.commit()
        }
        val secret = "history-access-test-secret-at-least-32-bytes"
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.PRODUCTION)).start().use { db ->
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity in active) UserSelf(identity.userId.toString(), "Test", null, "+79990000000", 20, null, now.toString()) else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = active.remove(identity)
            })
            application { configureTransport(); routing { chatReadRoutes(ChatReadService(db, secret), sessions) } }
            val now = Instant.now()
            fun bearer(id: UUID): String {
                val identity = identities.getValue(id)
                return "Bearer " + JwtTokenIssuer(secret).issue(id, identity.sessionId, 0, now.minusSeconds(1), now.plusSeconds(3600))
            }
            suspend fun search(q: String, cursor: String? = null, limit: Int = 1, id: UUID = chat, actor: UUID = member, status: HttpStatusCode = HttpStatusCode.OK): JsonObject {
                val response = client.get("/api/v1/chats/$id/messages/search") {
                    header(HttpHeaders.Authorization, bearer(actor)); parameter("q", q); parameter("limit", limit)
                    if (cursor != null) parameter("cursor", cursor)
                }
                assertEquals(status, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            fun seqs(page: JsonObject) = page.getValue("items").jsonArray.map { it.jsonObject.getValue("messageSeq").jsonPrimitive.long }
            fun next(page: JsonObject) = page.getValue("nextCursor").jsonPrimitive.content
            val first = search("  needle  ")
            assertEquals(listOf(3L), seqs(first))
            val cursor = next(first)
            db.withJdbcTransaction { c ->
                c.prepareStatement("UPDATE chats SET last_message_seq=8 WHERE id=?").use { it.setObject(1, chat); it.executeUpdate() }
                // Backdated insertion would appear on the next page without the saved upper sequence.
                c.prepareStatement("INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at) VALUES (?,?,7,?,'TEXT','needle inserted',timestamptz '2026-01-01 00:00:02.5+00')").use {
                    it.setObject(1, message); it.setObject(2, chat); it.setObject(3, member); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO messages (id,chat_id,message_seq,type,system_type,system_payload,created_at) VALUES (?,?,8,'SYSTEM','member_joined','{}'::jsonb,clock_timestamp())").use {
                    it.setObject(1, UUID.randomUUID()); it.setObject(2, chat); it.executeUpdate()
                }
            }
            val second = search("needle", cursor)
            assertEquals(listOf(2L), seqs(second))
            val last = search("needle", next(second))
            assertEquals(listOf(1L), seqs(last))
            assertEquals(JsonNull, last["nextCursor"])
            assertEquals(second, search("needle", cursor))
            assertEquals(listOf(3L, 7L, 2L, 1L), seqs(search("needle", limit=10)))
            assertEquals(listOf(4L), seqs(search("100%_")))
            assertEquals(listOf(4L), seqs(search("literal\\")))
            assertEquals(listOf(6L), seqs(search("🦊🦊🦊")))
            assertTrue(seqs(search("x".repeat(256))).isEmpty())
            for (q in listOf("", "  ", "ab", "🦊🦊", "x".repeat(257))) {
                assertEquals("INVALID_FIELD", search(q, status=HttpStatusCode.BadRequest).getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
            val badCursors = listOf(
                search("ordinary", cursor, status=HttpStatusCode.BadRequest),
                search("needle", cursor, limit=2, status=HttpStatusCode.BadRequest),
                search("needle", cursor, actor=outsider, status=HttpStatusCode.BadRequest),
                search("needle", cursor, id=UUID.randomUUID(), status=HttpStatusCode.BadRequest),
                search("needle", "A"+cursor, status=HttpStatusCode.BadRequest)
            )
            for (error in badCursors) {
                assertEquals("INVALID_CURSOR", error.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertFalse(error.toString().contains("needle one"))
                assertFalse(error.containsKey("items"))
            }
            for (actor in listOf(outsider, left)) {
                val error = search("needle", actor=actor, status=HttpStatusCode.Forbidden)
                assertEquals("NOT_MEMBER", error.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertFalse(error.containsKey("items"))
            }
            db.withReadOnlyJdbcTransaction { c ->
                c.prepareStatement("SELECT last_read_seq FROM chat_memberships WHERE chat_id=? AND user_id=?").use {
                    it.setObject(1, chat); it.setObject(2, member)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
            }
        }
    }
}
