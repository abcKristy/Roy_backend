package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.devices.DeviceService
import com.example.devices.DeviceTokenCipher
import com.example.devices.seedDeviceSession
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceRoutesIntegrationTest {
    @Test
    fun `device route validates pair and revokes installation`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step7devices$")))
        val dbUser = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val installationId = UUID.randomUUID()
        val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        val sessionId = DriverManager.getConnection(url, dbUser, dbPassword).use { connection ->
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'test','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement -> statement.setObject(1, actor); statement.setString(2, phone); statement.executeUpdate() }
            connection.seedDeviceSession(actor).sessionId
        }
        val secret = "device-route-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, dbUser, dbPassword, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        try {
            val sessions = SessionAccessService(secret, JdbcSessionRepository(runtime))
            val cipher = DeviceTokenCipher.fromBase64(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
            application {
                configureTransport()
                routing { deviceRoutes(DeviceService(runtime, cipher), sessions) }
            }
            val now = Instant.now()
            val token = "Bearer " + JwtTokenIssuer(secret).issue(actor, sessionId, 0,
                now.minusSeconds(10), now.plusSeconds(3600))
            suspend fun register(platform: String, provider: String, value: String = "valid-token") =
                client.put("/api/v1/me/push-devices/$installationId") {
                    header(HttpHeaders.Authorization, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"platform":"$platform","provider":"$provider","token":"$value"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, register("android", "apns").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, register("android", "rustore", " ").status)
            val first = register("android", "rustore")
            assertEquals(HttpStatusCode.OK, first.status)
            assertTrue(first.bodyAsText().contains(installationId.toString()))
            assertEquals(first.bodyAsText(), register("android", "rustore").bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/me/push-devices/$installationId") {
                contentType(ContentType.Application.Json)
                setBody("""{"platform":"android","provider":"rustore","token":"valid-token"}""")
            }.status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/me/push-devices/$installationId") {
                header(HttpHeaders.Authorization, token)
            }.status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/me/push-devices/$installationId") {
                header(HttpHeaders.Authorization, token)
            }.status)
        } finally {
            runtime.close()
        }
    }
}
