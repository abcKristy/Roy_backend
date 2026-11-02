package com.example.api

import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {
    @Test
    fun `health probes database on every request`() = testApplication {
        var ready = true
        var probes = 0
        application {
            configureTransport()
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady(): Boolean {
                    probes++
                    return ready
                }

                override fun close() = Unit
            })
        }

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        ready = false
        val failed = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        val failureBody = failed.bodyAsText()
        val error = Json.parseToJsonElement(failureBody).jsonObject
        assertEquals("SERVICE_UNAVAILABLE", error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(error["requestId"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(!failureBody.contains("SELECT", ignoreCase = true))
        assertTrue(!failureBody.contains("password", ignoreCase = true))
        ready = true
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(3, probes)
    }
}
