package com.example.api

import com.example.auth.JwtTokenIssuer
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import com.example.geocoding.ReverseGeocodeResult
import com.example.geocoding.ReverseGeocoder
import com.example.persistence.DatabaseRuntime
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

class GeocodingRouteTest {
    @Test
    fun `reverse returns provider none and safe unavailable while enforcing auth and position`() = testApplication {
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val secret = "geocoding-route-test-secret-at-least-32-bytes"
        val sessions = SessionAccessService(secret, object : SessionRepository {
            override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
                if (identity.userId == actor && identity.sessionId == session)
                    UserSelf(actor.toString(), "Test", null, "+79990000000", 20, null, now.toString()) else null
            override suspend fun revoke(identity: SessionIdentity, now: Instant) = false
        })
        var result: ReverseGeocodeResult = ReverseGeocodeResult.Found("Москва")
        var calls = 0
        val geocoder = ReverseGeocoder { calls++; result }
        application {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady() = true
                override fun close() = Unit
            }, sessionAccess = sessions, reverseGeocoder = geocoder)
        }
        val now = Instant.now()
        val token = JwtTokenIssuer(secret).issue(actor, session, 0, now.minusSeconds(10), now.plusSeconds(3600))
        suspend fun request(body: String, authenticated: Boolean = true) = client.post("/api/v1/geocoding/reverse") {
            if (authenticated) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val valid = """{"position":{"lat":55.75,"lon":37.62}}"""
        val found = request(valid)
        assertEquals(HttpStatusCode.OK, found.status)
        assertTrue(found.bodyAsText().contains("\"address\":\"Москва\""))
        result = ReverseGeocodeResult.None
        val none = request(valid)
        assertEquals(HttpStatusCode.OK, none.status)
        assertTrue(none.bodyAsText().contains("\"address\":null"))
        assertTrue(none.bodyAsText().contains("\"source\":\"none\""))
        result = ReverseGeocodeResult.Unavailable
        val unavailable = request(valid)
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertTrue(unavailable.bodyAsText().contains("GEOCODER_UNAVAILABLE"))
        assertEquals(HttpStatusCode.Unauthorized, request(valid, authenticated = false).status)
        assertEquals(HttpStatusCode.UnprocessableEntity,
            request("""{"position":{"lat":91,"lon":37.62}}""").status)
        assertEquals(3, calls)
    }
}
