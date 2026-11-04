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

class HistoryAccessIntegrationTest {
    @Test
    fun `history ACL applies to detail history search and revoked access`() = testApplication {
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
            c.prepareStatement("INSERT INTO chats (id,point_topic_id,last_message_seq,created_at) VALUES (?,?,1,clock_timestamp())").use {
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
            c.prepareStatement("INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at) VALUES (?,?,1,?,'TEXT',?,clock_timestamp())").use {
                it.setObject(1, message); it.setObject(2, chat); it.setObject(3, member); it.setString(4, privateText); it.executeUpdate()
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
            val paths = listOf("/api/v1/chats/$chat", "/api/v1/chats/$chat/messages", "/api/v1/chats/$chat/messages/search?q=Private")
            for (path in paths) {
                val ok = client.get(path) { header(HttpHeaders.Authorization, bearer(member)) }
                assertEquals(HttpStatusCode.OK, ok.status)
                assertTrue(ok.bodyAsText().contains(privateText))
                for (denied in listOf(outsider, left)) {
                    repeat(2) {
                        val response = client.get(path) { header(HttpHeaders.Authorization, bearer(denied)) }
                        assertEquals(HttpStatusCode.Forbidden, response.status)
                        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        assertEquals(setOf("error", "requestId"), error.keys)
                        assertEquals("NOT_MEMBER", error.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                        for (value in listOf(privateText, "Private author marker", "Private address marker", message.toString(), member.toString(), "unreadCount", "participantCount", "items", "author", "snippet")) {
                            assertFalse(error.toString().contains(value), "Denied response contains private history field")
                        }
                    }
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            }
            active.remove(identities.getValue(member))
            for (path in paths) assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(HttpHeaders.Authorization, bearer(member)) }.status)
            db.withReadOnlyJdbcTransaction { c ->
                c.prepareStatement("SELECT last_read_seq FROM chat_memberships WHERE chat_id=? AND user_id=?").use {
                    it.setObject(1, chat); it.setObject(2, member)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
            }
        }
    }
}
