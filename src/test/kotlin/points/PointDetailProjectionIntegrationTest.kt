package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Reads the guarded viewport fixture on a disposable PostGIS database. */
class PointDetailProjectionIntegrationTest {
    @Test
    fun `detail keeps distinct point people and per-topic chat counts`() = runBlocking {
        val url = System.getenv("TEST_VIEWPORT_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t4_viewport$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        HikariDatabaseBootstrap(
            ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.PRODUCTION)
        ).start().use { runtime ->
            val repository = PointReadRepository(runtime, "test-cursor-secret-at-least-thirty-two-bytes")
            val point = assertNotNull(repository.detail(MULTI_TOPIC_ID))
            assertEquals("Synthetic multi-topic point", point.address)
            assertEquals("MANUAL", point.addressSource)
            assertEquals(3, point.peopleCount)
            assertEquals(listOf("football", "other"), point.topics.map(PointTopicRead::presetKey))
            assertEquals(listOf(2, 2), point.topics.map(PointTopicRead::participantCount))
            assertEquals(listOf("Шахматы"), point.topics.filter { it.presetKey == "other" }.map(PointTopicRead::label))
            assertEquals(2, point.topics.map(PointTopicRead::chatId).distinct().size)

            val single = assertNotNull(repository.detail(SINGLE_TOPIC_ID))
            assertEquals(1, single.peopleCount)
            assertEquals(1, single.topics.single().participantCount)
            assertNull(repository.detail(UUID.fromString("20000000-0000-4000-8000-000000000099")))
        }
    }

    private companion object {
        val SINGLE_TOPIC_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000001")
        val MULTI_TOPIC_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
    }
}
