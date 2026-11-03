package com.example.api

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.PointReadRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PointFilterCursorIntegrationTest {
    @Test
    fun `cursor continues stable map order only for the same canonical filter`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t4_filter$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "cursor-test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            application {
                configureTransport()
                configureRouting(runtime, pointReadRepository = PointReadRepository(runtime,
                    "cursor-filter-signing-secret-at-least-32-bytes"))
            }
            val bbox = "west=37.619&south=55.749&east=37.626&north=55.756"
            val first = client.get("/api/v1/points?$bbox&limit=1")
            assertEquals(HttpStatusCode.OK, first.status)
            val firstBody = Json.parseToJsonElement(first.bodyAsText()).jsonObject
            assertEquals(listOf("20000000-0000-4000-8000-000000000002"),
                firstBody.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            val cursor = assertNotNull(firstBody.getValue("nextCursor").jsonPrimitive.content)

            // The cursor exposes only version, resource, filter hash, and public ordering keys.
            val payload = Base64.getUrlDecoder().decode(cursor.substringBefore('.')).toString(StandardCharsets.UTF_8)
            assertTrue(Regex("^1\\|points\\|[0-9a-f]{64}\\|[^|]+\\|[0-9a-f-]{36}$").matches(payload))
            assertTrue(!payload.contains("10000000-0000-4000-8000"))
            assertTrue(!payload.contains("+1202555010"))

            val second = client.get("/api/v1/points?$bbox&limit=1&peopleMin=0&peopleMax=50&cursor=$cursor")
            assertEquals(HttpStatusCode.OK, second.status)
            val secondBody = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals(listOf("20000000-0000-4000-8000-000000000001"),
                secondBody.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertEquals(JsonNull, secondBody.getValue("nextCursor"))

            val changedFilters = listOf(
                "$bbox&limit=1&topicKey=football",
                "$bbox&limit=1&topicKey=other&customTopicLabel=%D0%A8%D0%B0%D1%85%D0%BC%D0%B0%D1%82%D1%8B",
                "$bbox&limit=1&peopleMin=1",
                "west=37.620&south=55.749&east=37.626&north=55.756&limit=1",
                "$bbox&limit=2",
            )
            for (query in changedFilters) {
                val response = client.get("/api/v1/points?$query&cursor=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status, query)
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("INVALID_CURSOR", error.getValue("code").jsonPrimitive.content, query)
            }
            val tampered = (if (cursor.first() == 'A') 'B' else 'A') + cursor.drop(1)
            val bad = client.get("/api/v1/points?$bbox&limit=1&cursor=$tampered")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("INVALID_CURSOR", Json.parseToJsonElement(bad.bodyAsText()).jsonObject
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        }
    }
}
