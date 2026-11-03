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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class PointMapFilterValidationIntegrationTest {
    @Test
    fun `invalid filters return 400 while valid empty filter returns an empty page`() = testApplication {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@testApplication
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t4_filter$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "filter-validation-test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            application {
                configureTransport()
                configureRouting(runtime, pointReadRepository = PointReadRepository(runtime,
                    "filter-validation-cursor-secret-at-least-32-bytes"))
            }
            val viewport = "/api/v1/points?west=37.619&south=55.749&east=37.626&north=55.756"
            val invalid = listOf(
                "peopleMin=-1", "peopleMin=51", "peopleMax=-1", "peopleMax=51",
                "peopleMin=3&peopleMax=2", "peopleMin=nope", "peopleMax=1.5",
                "topicKey=unknown", "topicKey=", "customTopicLabel=Chess",
                "topicKey=football&customTopicLabel=Chess", "topicKey=other&customTopicLabel=%20%20",
                "topicKey=other&customTopicLabel=${"x".repeat(41)}",
                "peopleMin=1&peopleMin=2", "peopleMax=1&peopleMax=2",
                "topicKey=football&topicKey=other",
                "topicKey=other&customTopicLabel=Chess&customTopicLabel=Checkers",
            )
            for (query in invalid) {
                val response = client.get("$viewport&$query")
                assertEquals(HttpStatusCode.BadRequest, response.status, query)
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
                assertEquals("INVALID_FIELD", error["code"]?.jsonPrimitive?.content, query)
            }

            val empty = client.get("$viewport&peopleMin=0&peopleMax=0")
            assertEquals(HttpStatusCode.OK, empty.status)
            val emptyBody = Json.parseToJsonElement(empty.bodyAsText()).jsonObject
            assertEquals(0, emptyBody["items"]!!.jsonArray.size)

            val otherWithoutCustom = client.get("$viewport&topicKey=other&peopleMin=2&peopleMax=2")
            assertEquals(HttpStatusCode.OK, otherWithoutCustom.status)
            val otherItems = Json.parseToJsonElement(otherWithoutCustom.bodyAsText()).jsonObject["items"]!!.jsonArray
            assertEquals(listOf("20000000-0000-4000-8000-000000000002"),
                otherItems.map { it.jsonObject["id"]!!.jsonPrimitive.content })

            val inclusive = client.get("$viewport&topicKey=football&peopleMin=2&peopleMax=2")
            assertEquals(HttpStatusCode.OK, inclusive.status)
            assertEquals(listOf("20000000-0000-4000-8000-000000000002"),
                Json.parseToJsonElement(inclusive.bodyAsText()).jsonObject["items"]!!.jsonArray
                    .map { it.jsonObject["id"]!!.jsonPrimitive.content })
        }
    }
}
