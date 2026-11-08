package com.example.api

import com.example.accounts.AccountDeletionService
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class AccountDeletionConfirmationTest {
    @Test
    fun `quoted confirmation is rejected before deletion touches the database`() = testApplication {
        val secret = "synthetic-deletion-confirmation-secret"
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val token = JwtTokenIssuer(secret).issue(userId, UUID.randomUUID(), 0, now.minusSeconds(1), now.plusSeconds(60))
        val sessions = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf =
                UserSelf(userId.toString(), "Fixture", phone = "+79990000001", ageYears = 20, createdAt = now.toString())

            override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean =
                error("The fixture session must remain active")
        })
        val database = object : DatabaseRuntime {
            override suspend fun isReady() = true
            override fun close() = Unit
        }
        application {
            configureTransport()
            routing { accountDeletionRoutes(AccountDeletionService(database, PasswordHashingWork(Argon2idPasswordHasher())), sessions) }
        }

        val response = client.post("/api/v1/me/deletion") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"fixture-password","confirm":"true"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertContains(response.bodyAsText(), "INVALID_FIELD")
    }
}
