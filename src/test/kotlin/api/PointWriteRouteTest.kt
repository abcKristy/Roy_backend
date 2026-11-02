package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.persistence.DatabaseRuntime
import com.example.points.PointWriteService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointWriteRouteTest {
    @Test
    fun `unknown topic preset is rejected before database mutation`() = testApplication {
        val database = object : DatabaseRuntime {
            override suspend fun isReady() = true
            override fun close() = Unit
        }
        val secret = "point-route-test-secret-at-least-32-bytes"
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val sessions = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                if (identity.userId == actor && identity.sessionId == session)
                    UserSelf(actor.toString(), "Test", null, "+79990000000", 20, null, now.toString()) else null
            override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
        })
        application {
            configureTransport()
            configureRouting(database, sessionAccess = sessions, pointWriteService = PointWriteService(database))
        }
        val now = Instant.now()
        val token = JwtTokenIssuer(secret).issue(actor, session, 0, now.minusSeconds(10), now.plusSeconds(3600))
        val response = client.post("/api/v1/points") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("""{"position":{"lat":55.75,"lon":37.62},"topic":{"presetKey":"unknown","customLabel":null},"address":"Test","addressSource":"manual","confirmNearby":true}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("INVALID_FIELD"))

        val missingKey = client.post("/api/v1/points") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"position":{"lat":55.75,"lon":37.62},"topic":{"presetKey":"football","customLabel":null},"address":"Test","addressSource":"manual","confirmNearby":true}""")
        }
        assertEquals(HttpStatusCode.BadRequest, missingKey.status)
        assertTrue(missingKey.bodyAsText().contains("INVALID_FIELD"))
    }
}
