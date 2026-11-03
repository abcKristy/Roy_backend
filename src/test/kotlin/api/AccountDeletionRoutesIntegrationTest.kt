package com.example.api

import com.example.accounts.AccountDeletionService
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountDeletionRoutesIntegrationTest {
    @Test
    fun `confirmation and password are required and old token is rejected after deletion`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step8delete$").matches(url))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val secret = "deletion-route-secret-at-least-32-bytes"
        val actor = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        val hasher = Argon2idPasswordHasher()
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        try {
            DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
                connection.autoCommit = false
                connection.prepareStatement(
                    """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,?,'Test',20,'draft',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, actor); statement.setString(2, phone)
                    statement.setString(3, hasher.hash("correct-password")); statement.executeUpdate()
                }
                connection.prepareStatement(
                    """INSERT INTO auth_sessions(id,user_id,created_at,expires_at)
                       VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day')"""
                ).use { statement ->
                    statement.setObject(1, sessionId); statement.setObject(2, actor); statement.executeUpdate()
                }
                connection.commit()
            }
            val sessions = SessionAccessService(secret, JdbcSessionRepository(runtime))
            application {
                configureTransport()
                routing { accountDeletionRoutes(AccountDeletionService(runtime, PasswordHashingWork(hasher)), sessions) }
            }
            val now = Instant.now()
            val token = "Bearer " + JwtTokenIssuer(secret).issue(actor, sessionId, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            suspend fun deletion(body: String) = client.post("/api/v1/me/deletion") {
                header(HttpHeaders.Authorization, token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.UnprocessableEntity,
                deletion("""{"currentPassword":"correct-password","confirm":false}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity,
                deletion("""{"currentPassword":"incorrect","confirm":true}""").status)
            assertEquals(HttpStatusCode.NoContent,
                deletion("""{"currentPassword":"correct-password","confirm":true}""").status)
            assertEquals(HttpStatusCode.Unauthorized,
                deletion("""{"currentPassword":"correct-password","confirm":true}""").status)
        } finally { runtime.close() }
    }
}
