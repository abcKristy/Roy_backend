package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.devices.DeviceService
import com.example.devices.DeviceTokenCipher
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcSessionRepository
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceRegistrationSecurityIntegrationTest {
    @Test
    fun `older authenticated account cannot reclaim a rebound installation`() = testApplication {
        withDeviceRegistry { registry ->
            val old = registry.actor()
            val current = registry.actor()
            val installation = UUID.randomUUID()
            assertEquals(HttpStatusCode.OK, registry.put(old, installation.toString(), "old"))
            assertEquals(HttpStatusCode.OK, registry.put(current, installation.toString(), "old"))
            val bound = registry.binding(installation)
            assertEquals(current.userId, bound.userId)
            assertEquals(HttpStatusCode.NoContent, registry.delete(old, installation))
            val stale = registry.request(old, installation.toString(), "delayed-unseen-token")
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue(stale.bodyAsText().contains("STALE_STATE"))
            assertEquals(bound, registry.binding(installation))
            assertEquals(HttpStatusCode.NoContent, registry.delete(current, installation))
            val revoked = registry.binding(installation)
            assertTrue(revoked.revokedAt != null)
            assertEquals(HttpStatusCode.Conflict, registry.put(old, installation.toString(), "old"))
            assertEquals(revoked, registry.binding(installation))
            assertEquals(HttpStatusCode.OK, registry.put(current, installation.toString(), "old"))
            val restored = registry.binding(installation)
            assertEquals(null, restored.revokedAt)
            registry.sessions.logout(old.authorization)
            assertEquals(HttpStatusCode.Unauthorized, registry.put(old, installation.toString(), "old"))
            assertEquals(restored, registry.binding(installation))
        }
    }

    @Test
    fun `logout after route authentication prevents in-flight registration`() = testApplication {
        val checked = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        withDeviceRegistry(afterAuthentication = {
            checked.complete(Unit)
            proceed.await()
        }) { registry ->
            val actor = registry.actor()
            coroutineScope {
                val request = async { registry.put(actor, UUID.randomUUID().toString(), "late-token") }
                try {
                    withTimeout(5000) { checked.await() }
                    registry.sessions.logout(actor.authorization)
                } finally {
                    proceed.complete(Unit)
                }
                assertEquals(HttpStatusCode.Unauthorized, request.await())
            }
        }
    }

    @Test
    fun `failed rebind rolls back job cancellation and token history`() = testApplication {
        withDeviceRegistry { registry ->
            val old = registry.actor()
            val current = registry.actor()
            val installation = UUID.randomUUID()
            assertEquals(HttpStatusCode.OK, registry.put(old, installation.toString(), "existing"))
            val initial = registry.binding(installation)
            val job = registry.seedJob(old.userId, installation)
            registry.connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "ALTER TABLE device_registrations ADD CONSTRAINT fixture_reject_rebind " +
                        "CHECK (installation_id <> '$installation' OR user_id = '${old.userId}')"
                )
            }
            try {
                assertEquals(HttpStatusCode.InternalServerError, registry.put(current, installation.toString(), "new"))
                assertEquals(initial, registry.binding(installation))
                assertEquals("PENDING", registry.jobStatus(job))
            } finally {
                registry.connection.createStatement().use { statement ->
                    statement.executeUpdate("ALTER TABLE device_registrations DROP CONSTRAINT fixture_reject_rebind")
                }
            }
            assertEquals(HttpStatusCode.OK, registry.put(old, installation.toString(), "existing"))
            assertEquals(HttpStatusCode.OK, registry.put(current, installation.toString(), "existing"))
            assertEquals("CANCELLED", registry.jobStatus(job))
        }
    }

    @Test
    fun `retired token cannot undo rotation after account switch or within a session`() = testApplication {
        withDeviceRegistry { registry ->
            val old = registry.actor()
            val current = registry.actor()
            val installation = UUID.randomUUID()
            assertEquals(HttpStatusCode.OK, registry.put(old, installation.toString(), "retired-owner"))
            val initial = registry.request(current, installation.toString(), "first-current")
            assertEquals(HttpStatusCode.OK, initial.status)
            val before = registry.binding(installation)
            val rotated = registry.request(current, installation.toString(), "rotated")
            assertEquals(HttpStatusCode.OK, rotated.status)
            assertEquals(initial.bodyAsText(), rotated.bodyAsText())
            val after = registry.binding(installation)
            assertNotEquals(before.digest, after.digest)
            for (token in listOf("first-current", "retired-owner")) {
                val replay = registry.request(current, installation.toString(), token)
                assertEquals(HttpStatusCode.Conflict, replay.status)
                assertTrue(replay.bodyAsText().contains("STALE_STATE"))
            }
            assertEquals(rotated.bodyAsText(), registry.request(current, installation.toString(), "rotated").bodyAsText())
            assertEquals(after, registry.binding(installation))
        }
    }

    @Test
    fun `route accepts both provider pairs and rejects noncanonical uuid or another installation token`() =
        testApplication {
            withDeviceRegistry { registry ->
                val actor = registry.actor()
                val installation = UUID.fromString("a1111111-2222-4333-8444-555555555555")
                assertEquals(HttpStatusCode.BadRequest, registry.put(actor, "1-2-3-4-5", "token"))
                assertEquals(HttpStatusCode.BadRequest, registry.put(actor, installation.toString().uppercase(), "token"))
                assertEquals(HttpStatusCode.UnprocessableEntity,
                    registry.request(actor, installation.toString(), "token", "ios", "rustore").status)
                assertEquals(HttpStatusCode.OK,
                    registry.request(actor, installation.toString(), "token", "ios", "apns").status)
                val initial = registry.binding(installation)
                assertEquals(HttpStatusCode.OK,
                    registry.request(actor, installation.toString(), "token", "ios", "apns").status)
                assertEquals(initial, registry.binding(installation))
                val conflict = registry.request(actor, UUID.randomUUID().toString(), "token", "ios", "apns")
                assertEquals(HttpStatusCode.Conflict, conflict.status)
                assertTrue(conflict.bodyAsText().contains("DEVICE_TOKEN_IN_USE"))
                assertEquals(HttpStatusCode.OK, registry.put(actor, UUID.randomUUID().toString(), "token"))
            }
        }

    private suspend fun ApplicationTestBuilder.withDeviceRegistry(
        afterAuthentication: suspend () -> Unit = {},
        block: suspend (Registry) -> Unit,
    ) {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m5t125_devices$")))
        val user = checkNotNull(System.getenv("DATABASE_USER"))
        val password = checkNotNull(System.getenv("DATABASE_PASSWORD"))
        val secret = "fixture-secret-for-device-security-only"
        val cipher = DeviceTokenCipher.fromBase64(Base64.getEncoder().encodeToString(ByteArray(32)))
        HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL))
            .start().use { database ->
                val repository = JdbcSessionRepository(database)
                val sessions = SessionAccessService(secret, object : SessionRepository by repository {
                    override suspend fun findActive(identity: SessionIdentity, now: Instant) =
                        repository.findActive(identity, now)?.also { afterAuthentication() }
                })
                application {
                    configureTransport()
                    routing { deviceRoutes(DeviceService(database, cipher), sessions) }
                }
                DriverManager.getConnection(url, user, password).use { connection ->
                    block(Registry(this, connection, JwtTokenIssuer(secret), sessions))
                }
            }
    }

    private data class Actor(val userId: UUID, val authorization: String)

    private data class Binding(
        val userId: UUID,
        val digest: String,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
        val revokedAt: OffsetDateTime?,
    )

    private class Registry(
        val app: ApplicationTestBuilder,
        val connection: Connection,
        val issuer: JwtTokenIssuer,
        val sessions: SessionAccessService,
    ) {
        private val createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(120)

        fun actor(): Actor {
            val userId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val now = Instant.now()
            val expiresAt = now.plusSeconds(600)
            val phone = "+7" + userId.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'fixture','Fixture',20,'fixture',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, phone)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO auth_sessions (id,user_id,created_at,expires_at) VALUES (?,?,?,?)"
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.setObject(3, createdAt.atOffset(ZoneOffset.UTC))
                statement.setObject(4, expiresAt.atOffset(ZoneOffset.UTC))
                assertEquals(1, statement.executeUpdate())
            }
            return Actor(userId, "Bearer " + issuer.issue(userId, sessionId, 0, createdAt, expiresAt))
        }

        suspend fun request(
            actor: Actor, installationId: String, token: String, platform: String = "android", provider: String = "rustore",
        ): HttpResponse = app.client.put("/api/v1/me/push-devices/$installationId") {
            header(HttpHeaders.Authorization, actor.authorization)
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"$platform","provider":"$provider","token":"$token"}""")
        }

        suspend fun put(actor: Actor, installationId: String, token: String): HttpStatusCode =
            request(actor, installationId, token).status

        suspend fun delete(actor: Actor, installation: UUID): HttpStatusCode =
            app.client.delete("/api/v1/me/push-devices/$installation") {
                header(HttpHeaders.Authorization, actor.authorization)
            }.status

        fun seedJob(userId: UUID, installation: UUID): UUID {
            val event = UUID.randomUUID()
            val job = UUID.randomUUID()
            connection.prepareStatement(
                """INSERT INTO notification_events (id,recipient_user_id,channel,event_type,dedupe_key,
                   display_snapshot,visible_in_app,push_requested,created_at)
                   VALUES (?,?,'PERSONAL','chat_message',?,'{}',true,true,clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, event)
                statement.setObject(2, userId)
                statement.setString(3, event.toString())
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """INSERT INTO push_delivery_jobs (id,notification_event_id,installation_id,next_attempt_at)
                   VALUES (?,?,?,clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, job)
                statement.setObject(2, event)
                statement.setObject(3, installation)
                assertEquals(1, statement.executeUpdate())
            }
            return job
        }

        fun jobStatus(id: UUID): String = connection.prepareStatement(
            "SELECT status FROM push_delivery_jobs WHERE id=?"
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getString(1)
            }
        }

        fun binding(installation: UUID): Binding = connection.prepareStatement(
            "SELECT user_id,token_digest,created_at,updated_at,revoked_at FROM device_registrations WHERE installation_id=?"
        ).use { statement ->
            statement.setObject(1, installation)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                Binding(rows.getObject(1, UUID::class.java), rows.getString(2),
                    rows.getObject(3, OffsetDateTime::class.java), rows.getObject(4, OffsetDateTime::class.java),
                    rows.getObject(5, OffsetDateTime::class.java))
            }
        }
    }
}
