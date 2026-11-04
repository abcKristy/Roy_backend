package com.example.api

import com.example.auth.*
import com.example.chats.ChatReadService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import io.ktor.client.request.get
import io.ktor.client.request.header
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

class HistoryPaginationIntegrationTest {
    @Test
    fun `sequence pages remain disjoint across inserts and invalid input preserves cursor`() = testApplication {
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
            c.prepareStatement("INSERT INTO chats (id,point_topic_id,last_message_seq,created_at) VALUES (?,?,5,clock_timestamp())").use {
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
            for (seq in 1..5) {
                c.prepareStatement("INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at) VALUES (?,?,?,?,'TEXT',?,clock_timestamp())").use {
                    it.setObject(1, UUID.randomUUID()); it.setObject(2, chat); it.setInt(3, seq); it.setObject(4, member); it.setString(5, "Page text $seq"); it.executeUpdate()
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
            suspend fun page(query: String): JsonObject {
                val response = client.get("/api/v1/chats/$chat/messages?$query") { header(HttpHeaders.Authorization, bearer(member)) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            fun seqs(page: JsonObject) = page.getValue("items").jsonArray.map { it.jsonObject.getValue("messageSeq").jsonPrimitive.long }
            fun ids(page: JsonObject) = page.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            val latest = page("limit=2")
            assertEquals(listOf(5L, 4L), seqs(latest))
            assertEquals(4L, latest.getValue("nextBeforeSeq").jsonPrimitive.long)
            assertEquals(JsonNull, latest["nextAfterSeq"])
            db.withJdbcTransaction { c ->
                c.prepareStatement("UPDATE chats SET last_message_seq=6 WHERE id=?").use {
                    it.setObject(1, chat); assertEquals(1, it.executeUpdate())
                }
                c.prepareStatement("INSERT INTO messages (id,chat_id,message_seq,type,system_type,system_payload,created_at) VALUES (?,?,6,'SYSTEM','member_joined','{}'::jsonb,clock_timestamp())").use {
                    it.setObject(1, message); it.setObject(2, chat); assertEquals(1, it.executeUpdate())
                }
            }
            val older = page("beforeSeq=4&limit=2")
            assertEquals(listOf(3L, 2L), seqs(older))
            assertEquals(2L, older.getValue("nextBeforeSeq").jsonPrimitive.long)
            val oldest = page("beforeSeq=2&limit=2")
            assertEquals(listOf(1L), seqs(oldest))
            assertEquals(JsonNull, oldest["nextBeforeSeq"])
            val collected = ids(latest) + ids(older) + ids(oldest)
            assertEquals(5, collected.toSet().size)
            val delta = page("afterSeq=3&limit=2")
            assertEquals(listOf(4L, 5L), seqs(delta))
            assertEquals(5L, delta.getValue("nextAfterSeq").jsonPrimitive.long)
            assertEquals(JsonNull, delta["nextBeforeSeq"])
            val rest = page("afterSeq=5&limit=2")
            assertEquals(listOf(6L), seqs(rest))
            assertEquals(message.toString(), ids(rest).single())
            assertEquals("system", rest.getValue("items").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(JsonNull, rest["nextAfterSeq"])
            val exactEnd = page("afterSeq=4&limit=2")
            assertEquals(listOf(5L, 6L), seqs(exactEnd))
            assertEquals(JsonNull, exactEnd["nextAfterSeq"])
            assertEquals(seqs(exactEnd), seqs(page("afterSeq=4&limit=2")))
            assertEquals(JsonNull, page("beforeSeq=3&limit=2")["nextBeforeSeq"])
            assertTrue(page("afterSeq=6&limit=2").getValue("items").jsonArray.isEmpty())
            for (query in listOf("beforeSeq=broken", "afterSeq=broken", "beforeSeq=0", "afterSeq=-1", "beforeSeq=2&afterSeq=1", "afterSeq=9223372036854775808")) {
                val response = client.get("/api/v1/chats/$chat/messages?$query") { header(HttpHeaders.Authorization, bearer(member)) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("INVALID_FIELD", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
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
