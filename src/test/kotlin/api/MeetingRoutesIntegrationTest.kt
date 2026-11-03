package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.meetings.BookingService
import com.example.meetings.ProposalService
import com.example.persistence.HikariDatabaseBootstrap
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeetingRoutesIntegrationTest {
    @Test
    fun `proposal voting booking and close are reachable only to members`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step6routes$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "meeting-route-test-secret-at-least-32-bytes"
        val actor = UUID.randomUUID()
        val friend = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            listOf(actor, friend, stranger).forEach { insertUser(connection, it) }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, point); statement.setObject(2, actor); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())")
                .use { statement -> statement.setObject(1, topic); statement.setObject(2, point); statement.executeUpdate() }
            connection.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())")
                .use { statement -> statement.setObject(1, chat); statement.setObject(2, topic); statement.executeUpdate() }
            listOf(actor, friend).forEach { id ->
                connection.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())")
                    .use { statement -> statement.setObject(1, chat); statement.setObject(2, id); statement.executeUpdate() }
            }
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        try {
            val sessions = SessionAccessService(secret, object : SessionRepository {
                override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                    if (identity.userId in setOf(actor, friend, stranger))
                        UserSelf(identity.userId.toString(), "Test", null, "+79990000000", 20, null, now.toString())
                    else null
                override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
            })
            application {
                configureTransport()
                routing { meetingRoutes(ProposalService(runtime, secret), BookingService(runtime), sessions) }
            }
            val issuer = JwtTokenIssuer(secret)
            val now = Instant.now()
            fun bearer(id: UUID) = "Bearer " + issuer.issue(id, UUID.randomUUID(), 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            val actorToken = bearer(actor)
            val friendToken = bearer(friend)
            val strangerToken = bearer(stranger)
            val startsAt = now.plusSeconds(86_400).toString()
            val endsAt = now.plusSeconds(90_000).toString()
            val createKey = UUID.randomUUID()
            val invalidInterval = client.post("/api/v1/chats/$chat/proposals") {
                header(HttpHeaders.Authorization, actorToken)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"startsAt":"$endsAt","endsAt":"$startsAt"}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, invalidInterval.status)
            suspend fun create() = client.post("/api/v1/chats/$chat/proposals") {
                header(HttpHeaders.Authorization, actorToken)
                header("Idempotency-Key", createKey.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"startsAt":"$startsAt","endsAt":"$endsAt"}""")
            }
            val created = create()
            assertEquals(HttpStatusCode.Created, created.status)
            val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals("0", createdBody["goingCount"]?.jsonPrimitive?.content)
            val proposalId = createdBody["id"]!!.jsonPrimitive.content
            assertEquals(createdBody, Json.parseToJsonElement(create().bodyAsText()).jsonObject)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/chats/$chat/proposals") {
                header(HttpHeaders.Authorization, strangerToken)
            }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/chats/$chat/proposals") {
                header(HttpHeaders.Authorization, actorToken)
            }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/proposals/$proposalId") {
                header(HttpHeaders.Authorization, actorToken)
            }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/proposals/$proposalId/votes") {
                header(HttpHeaders.Authorization, actorToken)
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/proposals/$proposalId/votes") {
                header(HttpHeaders.Authorization, strangerToken)
            }.status)

            val noOwnVote = client.post("/api/v1/proposals/$proposalId/booking") {
                header(HttpHeaders.Authorization, actorToken)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"confirmOverlap":false,"expectedRevision":0}""")
            }
            assertEquals(HttpStatusCode.Conflict, noOwnVote.status)
            assertTrue(noOwnVote.bodyAsText().contains("VOTE_REQUIRED"))

            suspend fun vote(token: String) = client.put("/api/v1/proposals/$proposalId/vote") {
                header(HttpHeaders.Authorization, token)
                contentType(ContentType.Application.Json)
                setBody("""{"going":true}""")
            }
            val firstVote = vote(actorToken)
            assertEquals(HttpStatusCode.OK, firstVote.status)
            val oneVoteRevision = Json.parseToJsonElement(firstVote.bodyAsText()).jsonObject["revision"]!!.jsonPrimitive.content
            val oneVoteBook = client.post("/api/v1/proposals/$proposalId/booking") {
                header(HttpHeaders.Authorization, actorToken)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"confirmOverlap":false,"expectedRevision":$oneVoteRevision}""")
            }
            assertEquals(HttpStatusCode.Conflict, oneVoteBook.status)
            assertTrue(oneVoteBook.bodyAsText().contains("INSUFFICIENT_VOTES"))
            val secondVote = vote(friendToken)
            assertEquals(HttpStatusCode.OK, secondVote.status)
            val revision = Json.parseToJsonElement(secondVote.bodyAsText()).jsonObject["revision"]!!.jsonPrimitive.content
            val bookKey = UUID.randomUUID()
            suspend fun book(expectedRevision: String) = client.post("/api/v1/proposals/$proposalId/booking") {
                header(HttpHeaders.Authorization, actorToken)
                header("Idempotency-Key", bookKey.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"confirmOverlap":false,"expectedRevision":$expectedRevision}""")
            }
            assertEquals(HttpStatusCode.Conflict, book("0").status)
            val booked = book(revision)
            assertEquals(HttpStatusCode.Created, booked.status)
            val bookedBody = Json.parseToJsonElement(booked.bodyAsText()).jsonObject
            assertEquals("booked", bookedBody["status"]?.jsonPrimitive?.content)
            assertEquals("active", bookedBody["booking"]!!.jsonObject["status"]?.jsonPrimitive?.content)
            assertEquals(bookedBody, Json.parseToJsonElement(book(revision).bodyAsText()).jsonObject)
            val closeRevision = bookedBody["revision"]!!.jsonPrimitive.content
            val notAuthor = client.post("/api/v1/proposals/$proposalId/close") {
                header(HttpHeaders.Authorization, friendToken)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":$closeRevision}""")
            }
            assertEquals(HttpStatusCode.Forbidden, notAuthor.status)
            val close = client.post("/api/v1/proposals/$proposalId/close") {
                header(HttpHeaders.Authorization, actorToken)
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":$closeRevision}""")
            }
            assertEquals(HttpStatusCode.OK, close.status)
            assertEquals("closed", Json.parseToJsonElement(close.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)
            assertTrue(client.get("/api/v1/proposals/$proposalId") {
                header(HttpHeaders.Authorization, friendToken)
            }.bodyAsText().contains(proposalId))
        } finally {
            runtime.close()
        }
    }

    private fun insertUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { statement -> statement.setObject(1, id); statement.executeUpdate() }
    }
}
