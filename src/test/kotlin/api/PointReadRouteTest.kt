package com.example.api

import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.persistence.DatabaseRuntime
import com.example.points.PointReadRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointReadRouteTest {
    @Test
    fun `public map validation route is mounted without authentication service`() = testApplication {
        val database = object : DatabaseRuntime {
            override suspend fun isReady() = true
            override fun close() = Unit
        }
        application {
            configureTransport()
            configureRouting(database, pointReadRepository = PointReadRepository(database,
                "test-only-route-secret-with-32-characters"))
        }
        val response = client.get("/api/v1/points?west=38&south=55&east=37&north=56")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("INVALID_BBOX"))
    }

    @Test
    fun `bbox and other filter errors have distinct response codes`() = testApplication {
        val database = object : DatabaseRuntime {
            override suspend fun isReady() = true
            override fun close() = Unit
        }
        val secret = "test-only-route-secret-with-32-characters"
        val sessions = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? = null
            override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
        })
        application {
            configureTransport()
            configureRouting(database, sessionAccess = sessions,
                pointReadRepository = PointReadRepository(database, secret))
        }

        val bbox = client.get("/api/v1/points?west=38&south=55&east=37&north=56")
        assertEquals(HttpStatusCode.BadRequest, bbox.status)
        assertTrue(bbox.bodyAsText().contains("INVALID_BBOX"))

        val filter = client.get("/api/v1/points?west=37&south=55&east=38&north=56&peopleMin=51")
        assertEquals(HttpStatusCode.BadRequest, filter.status)
        assertTrue(filter.bodyAsText().contains("INVALID_FIELD"))

        val unknownTopic = client.get("/api/v1/points?west=37&south=55&east=38&north=56&topicKey=unknown")
        assertEquals(HttpStatusCode.BadRequest, unknownTopic.status)
        assertTrue(unknownTopic.bodyAsText().contains("INVALID_FIELD"))

        val duplicateBbox = client.get("/api/v1/points?west=37&west=200&south=55&east=38&north=56")
        assertEquals(HttpStatusCode.BadRequest, duplicateBbox.status)
        assertTrue(duplicateBbox.bodyAsText().contains("INVALID_BBOX"))

        val duplicateLimit = client.get("/api/v1/points?west=37&south=55&east=38&north=56&limit=1&limit=200")
        assertEquals(HttpStatusCode.BadRequest, duplicateLimit.status)
        assertTrue(duplicateLimit.bodyAsText().contains("INVALID_FIELD"))

        val duplicateCursor = client.get("/api/v1/points?west=37&south=55&east=38&north=56&cursor=a&cursor=b")
        assertEquals(HttpStatusCode.BadRequest, duplicateCursor.status)
        assertTrue(duplicateCursor.bodyAsText().contains("INVALID_CURSOR"))
    }
}
