package com.example.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

class SafeErrorEnvelopeTest {
    @Test
    fun `explicit client failures preserve status code and nullable contract fields`() {
        val mapper = ErrorMapper()
        for (status in listOf(400, 401, 403, 404, 409, 422, 429)) {
            val failure = mapper.map(ApiException(HttpStatusCode.fromValue(status), "SAFE_$status", "Безопасная ошибка"))
            assertEquals(status, failure.status.value)
            assertEquals("SAFE_$status", failure.error.code)
            assertEquals("Безопасная ошибка", failure.error.message)
            val json = Json.parseToJsonElement(transportJson.encodeToString(
                ErrorResponse.serializer(), ErrorResponse(failure.error, "test-request"))
            ).jsonObject.getValue("error").jsonObject
            assertEquals(setOf("code", "message", "details", "retryAfterSeconds"), json.keys)
            assertEquals("null", json.getValue("details").toString())
            assertEquals("null", json.getValue("retryAfterSeconds").toString())
        }
    }

    @Test
    fun `unexpected SQL and malformed JSON return safe correlated envelopes`() = testApplication {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(captured)
        try {
        application {
            configureTransport()
            routing {
                get("/unexpected") { throw SQLException("SELECT secret_password FROM private_table") }
                get("/typed") { throw ApiException(HttpStatusCode.Conflict, "TEST_CONFLICT", "Safe conflict") }
                post("/body") { call.receive<Map<String, String>>() }
            }
        }
        val requestId = UUID.randomUUID().toString()
        for ((path, status, code) in listOf(
            Triple("/unexpected", HttpStatusCode.InternalServerError, "INTERNAL_ERROR"),
            Triple("/typed", HttpStatusCode.Conflict, "TEST_CONFLICT"),
        )) {
            val response = client.get(path) { header("X-Request-Id", requestId) }
            assertEquals(status, response.status)
            val body = response.bodyAsText()
            val envelope = Json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("error", "requestId"), envelope.keys)
            assertEquals(code, envelope.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(requestId, envelope.getValue("requestId").jsonPrimitive.content)
            assertEquals(requestId, response.headers["X-Request-Id"])
            for (secret in listOf("SELECT", "secret_password", "private_table", "SQLException")) {
                assertFalse(body.contains(secret))
            }
        }
        val malformed = client.post("/body") {
            contentType(ContentType.Application.Json)
            header("X-Request-Id", "invalid-id")
            setBody("{broken secret_password")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val body = malformed.bodyAsText()
        val envelope = Json.parseToJsonElement(body).jsonObject
        assertEquals("INVALID_JSON", envelope.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        UUID.fromString(envelope.getValue("requestId").jsonPrimitive.content)
        assertEquals(malformed.headers["X-Request-Id"], envelope.getValue("requestId").jsonPrimitive.content)
        assertFalse(body.contains("secret_password"))
        assertFalse(body.contains("invalid-id"))
        val failures = captured.list.filter { it.formattedMessage.contains("code=INTERNAL_ERROR") }
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.any { it.formattedMessage.contains("requestId=$requestId") &&
            it.formattedMessage.contains("exception=java.sql.SQLException") })
        for (event in failures) {
            assertEquals(null, event.throwableProxy)
            for (secret in listOf("SELECT", "secret_password", "private_table")) {
                assertFalse(event.formattedMessage.contains(secret))
            }
        }
        } finally {
            logger.detachAppender(captured)
            captured.stop()
        }
    }
}
