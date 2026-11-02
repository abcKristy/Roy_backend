package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Reads the disposable M1.T4 viewport SQL fixture; never writes to the database. */
class ViewportFixtureIntegrationTest {
    @Test
    fun `bbox pages cover both fixture points once and cursor belongs to filter`() = runBlocking {
        val url = System.getenv("TEST_VIEWPORT_DATABASE_URL") ?: return@runBlocking
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t4_viewport$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val runtime = HikariDatabaseBootstrap(
            ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.PRODUCTION)
        ).start()
        runtime.use {
            val repository = PointReadRepository(it, "test-cursor-secret-at-least-thirty-two-bytes")
            val filter = PointMapFilter(37.61, 55.74, 37.63, 55.76, limit = 1)
            val first = repository.page(filter)
            assertEquals(listOf(MULTI_TOPIC_ID), first.items.map(PointRead::id))
            assertEquals(3, first.items.single().peopleCount)
            assertEquals(listOf(2, 2), first.items.single().topics.map(PointTopicRead::participantCount))

            val cursor = assertNotNull(first.nextCursor)
            val second = repository.page(filter, cursor)
            assertEquals(listOf(SINGLE_TOPIC_ID), second.items.map(PointRead::id))
            assertEquals(1, second.items.single().peopleCount)
            assertNull(second.nextCursor)
            assertEquals(2, (first.items + second.items).map(PointRead::id).distinct().size)

            assertFailsWith<IllegalArgumentException> { repository.page(filter.copy(east = 37.625), cursor) }
            assertEquals(listOf(SINGLE_TOPIC_ID), repository.page(filter.copy(east = 37.623)).items.map(PointRead::id))
            assertNull(repository.detail(UUID.fromString("20000000-0000-4000-8000-000000000099")))
        }
    }

    private companion object {
        val SINGLE_TOPIC_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000001")
        val MULTI_TOPIC_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
    }
}
