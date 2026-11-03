package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PointMapFilterSqlIntegrationTest {
    @Test
    fun `map SQL applies topic counts and any topic within bbox`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t4_filter$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val single = UUID.fromString("20000000-0000-4000-8000-000000000001")
        val multi = UUID.fromString("20000000-0000-4000-8000-000000000002")

        HikariDatabaseBootstrap(ServerConfig(url, user, password,
            "map-filter-test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val read = PointReadRepository(runtime, "map-filter-cursor-secret-at-least-32-bytes")
            val viewport = PointMapFilter(37.619, 55.749, 37.626, 55.756)

            // The multi-topic point has three unique people, but each topic has two participants.
            val anyTopicAtTwo = read.page(viewport.copy(peopleMin = 2, peopleMax = 2))
            assertEquals(listOf(multi), anyTopicAtTwo.items.map { it.id })
            assertEquals(3, anyTopicAtTwo.items.single().peopleCount)
            assertEquals(listOf(2, 2), anyTopicAtTwo.items.single().topics.map { it.participantCount })
            assertNull(anyTopicAtTwo.nextCursor)
            assertEquals(emptyList(), read.page(viewport.copy(peopleMin = 3, peopleMax = 3)).items)

            // With a selected topic, bounds apply to that topic's chat, not Point.peopleCount.
            assertEquals(listOf(single), read.page(viewport.copy(topicKey = "football",
                peopleMin = 1, peopleMax = 1)).items.map { it.id })
            assertEquals(listOf(multi), read.page(viewport.copy(topicKey = "football",
                peopleMin = 2, peopleMax = 2)).items.map { it.id })
            assertEquals(listOf(multi), read.page(viewport.copy(topicKey = "other",
                customTopicLabel = "  ШАХМАТЫ  ", peopleMin = 2, peopleMax = 2)).items.map { it.id })
            assertEquals(emptyList(), read.page(viewport.copy(topicKey = "other",
                customTopicLabel = "Шашки", peopleMin = 2, peopleMax = 2)).items)

            // A matching topic outside the viewport must still be excluded.
            val singleOnly = viewport.copy(west = 37.619, south = 55.749, east = 37.621, north = 55.751)
            assertEquals(emptyList(), read.page(singleOnly.copy(peopleMin = 2, peopleMax = 2)).items)
            assertEquals(listOf(single), read.page(singleOnly.copy(peopleMin = 1,
                peopleMax = 1)).items.map { it.id })
        }
    }
}
