package com.example.auth

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshRouteTest {
    @Test
    fun `refresh endpoint accepts token without bearer and returns rotated session`() = testApplication {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val sessionId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val original = RefreshTokens.create(sessionId)
        val profile = UserSelf(userId.toString(), "Аня", null, "+79991234567", 24, null, now.toString())
        val rotations = AtomicInteger()
        val service = RefreshService(RefreshRepository { _, presented, _, _ ->
            rotations.incrementAndGet()
            if (presented == RefreshTokens.hash(original))
                RefreshRotationResult.Rotated(profile, userId, 0, now.plusSeconds(3600))
            else RefreshRotationResult.Invalid
        }, TokenIssuer { _, _, _, _, _ -> "new-access" }, Clock.fixed(now, ZoneOffset.UTC))
        val configure: Application.() -> Unit = {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady() = true
                override fun close() = Unit
            }, refreshService = service)
        }
        application(configure)
        for (accept in listOf("application/xml", "application/json;q=0, */*;q=1", "invalid, application/json")) {
            val rejected = client.post("/api/v1/auth/refresh") {
                header(HttpHeaders.Accept, accept)
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$original"}""")
            }
            assertEquals(HttpStatusCode.NotAcceptable, rejected.status)
            assertEquals("", rejected.bodyAsText())
            assertEquals(0, rotations.get())
        }
        // TestApplication folds repeated headers. Use Netty for their actual wire representation.
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { configure() }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val body = """{"refreshToken":"$original"}""".encodeToByteArray()
            assertEquals(406, postRaw(port, body, "Content-Type: application/json",
                "Accept: application/xml", "Accept: application/json"))
            assertEquals(415, postRaw(port, body, "Content-Type: invalid"))
            assertEquals(0, rotations.get())
        } finally {
            server.stop(0, 5_000)
        }
        for (mediaType in listOf(ContentType.Text.Plain, ContentType.parse("application/json; charset=iso-8859-1"))) {
            val rejected = client.post("/api/v1/auth/refresh") {
                contentType(mediaType)
                setBody("""{"refreshToken":"$original"}""")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, rejected.status)
            assertTrue(rejected.bodyAsText().contains("UNSUPPORTED_MEDIA_TYPE"))
            assertEquals(0, rotations.get())
        }
        val response = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$original"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, rotations.get())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.bodyAsText().contains("\"accessToken\":\"new-access\""))
        assertTrue(response.bodyAsText().contains("\"refreshToken\":"))

        val malformed = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"invalid"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, malformed.status)
        assertTrue(malformed.bodyAsText().contains("SESSION_EXPIRED"))
    }

    private fun postRaw(port: Int, body: ByteArray, vararg headers: String): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val head = listOf("POST /api/v1/auth/refresh HTTP/1.1", "Host: 127.0.0.1:$port",
                "Content-Length: ${body.size}", "Connection: close", *headers)
                .joinToString("\r\n", postfix = "\r\n\r\n")
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }
}
