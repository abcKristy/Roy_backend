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
import kotlin.test.assertTrue

class TopicPresetsRouteIntegrationTest {
    @Test
    fun `public catalog returns all eleven lowercase database presets including other`() = testApplication {
        val url = com.example.testing.testDatabaseUrl("TEST_VIEWPORT_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t4_viewport$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val runtime = HikariDatabaseBootstrap(
            ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.PRODUCTION)
        ).start()
        try {
            application {
                configureTransport()
                configureRouting(runtime, pointReadRepository = PointReadRepository(
                    runtime, "test-cursor-secret-at-least-thirty-two-bytes"))
            }
            val response = client.get("/api/v1/topics/presets")
            assertEquals(HttpStatusCode.OK, response.status)
            val items = Json.parseToJsonElement(response.bodyAsText()).jsonObject["items"]!!.jsonArray
            val keys = items.map { it.jsonObject["key"]!!.jsonPrimitive.content }
            assertEquals(listOf(
                "football", "volleyball", "basketball", "tennis", "running", "workout",
                "table_tennis", "badminton", "board_games", "mafia", "other",
            ), keys)
            assertTrue(items.all { it.jsonObject["label"]!!.jsonPrimitive.content.isNotBlank() })
        } finally {
            runtime.close()
        }
    }
}
