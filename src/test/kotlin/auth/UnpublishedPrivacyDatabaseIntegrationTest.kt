package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcRegistrationRepository
import com.example.testing.testDatabaseUrl
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnpublishedPrivacyDatabaseIntegrationTest {
    @Test
    fun `unpublished policy refuses draft consent before real database writes`() = testApplication {
        val url = testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_privacy$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        fun counts() = DriverManager.getConnection(url, user, password).use { connection ->
            listOf("users", "auth_sessions", "notification_settings").map { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
            }
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret-at-least-thirty-two-bytes-long"))
            .start().use { runtime ->
                val service = RegistrationService(UnpublishedPrivacyPolicy,
                    PasswordHashingWork(Argon2idPasswordHasher()), JdbcRegistrationRepository(runtime),
                    JwtTokenIssuer("test-only-secret-at-least-thirty-two-bytes-long"))
                application {
                    configureTransport()
                    configureRouting(runtime, registrationService = service)
                }
                val before = counts()
                val metadata = client.get("/api/v1/legal/privacy")
                assertEquals(HttpStatusCode.ServiceUnavailable, metadata.status)
                val response = client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"phone":"+79990000001","password":"test-password-only","displayName":"Test","ageYears":24,"acceptedPrivacyPolicyVersion":"${DraftPrivacyPolicy.VERSION}"}""")
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertTrue(response.headers["X-Request-Id"].orEmpty().isNotBlank())
                val body = response.bodyAsText()
                assertTrue(body.contains("SERVICE_UNAVAILABLE"))
                assertFalse(body.contains("test-password-only"))
                assertEquals(before, counts())
            }
    }
}
