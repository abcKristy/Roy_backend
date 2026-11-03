package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointReadRepositoryIntegrationTest {
    @Test
    fun `map filters before keyset and hydrates all topics with distinct people`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(url.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step4read$")))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val runtime = HikariDatabaseBootstrap(
            ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.PRODUCTION)
        ).start()
        val repository = PointReadRepository(runtime, "test-cursor-secret-at-least-thirty-two-bytes")
        val actor = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val firstFootball = UUID.randomUUID()
        val firstOther = UUID.randomUUID()
        val secondFootball = UUID.randomUUID()
        val firstFootballChat = UUID.randomUUID()
        val firstOtherChat = UUID.randomUUID()
        val secondFootballChat = UUID.randomUUID()
        val baseLon = 30.0 + Math.random() * 10.0
        val baseLat = 50.0 + Math.random() * 10.0
        runtime.use {
            it.withJdbcTransaction { connection ->
                seedUser(connection, actor)
                seedPoint(connection, first, actor, baseLon, baseLat, 1)
                seedPoint(connection, second, actor, baseLon + 0.01, baseLat, 2)
                seedTopic(connection, firstFootball, firstFootballChat, first, "football", null)
                seedTopic(connection, firstOther, firstOtherChat, first, "other", "Шахматы")
                seedTopic(connection, secondFootball, secondFootballChat, second, "football", null)
                listOf(firstFootballChat, firstOtherChat).forEach { chat ->
                    sql(connection, "INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, actor)
                }
                sql(connection,
                    "INSERT INTO point_creation_events (id,creator_user_id,point_id,created_at) VALUES (?,?,?,clock_timestamp())",
                    UUID.randomUUID(), actor, first)
            }
            assertEquals(11, repository.presets().size)
            val filter = PointMapFilter(baseLon - 0.001, baseLat - 0.001, baseLon + 0.011, baseLat + 0.001,
                peopleMin = 0, peopleMax = 1, limit = 1)
            val firstPage = repository.page(filter)
            assertEquals(1, firstPage.items.size)
            assertEquals(first, firstPage.items.single().id)
            assertEquals(1, firstPage.items.single().peopleCount)
            assertEquals(2, firstPage.items.single().topics.size)
            assertEquals(listOf(1, 1), firstPage.items.single().topics.map { topic -> topic.participantCount })
            val cursor = assertNotNull(firstPage.nextCursor)
            val nextPage = repository.page(filter, cursor)
            assertEquals(second, nextPage.items.single().id)
            assertNull(nextPage.nextCursor)
            assertFailsWith<IllegalArgumentException> { repository.page(filter.copy(peopleMin = 1), cursor) }
            assertFailsWith<IllegalArgumentException> { repository.page(filter, cursor + "tamper") }

            val footballOnly = repository.page(filter.copy(topicKey = "football", peopleMin = 1, limit = 30))
            assertEquals(listOf(first), footballOnly.items.map(PointRead::id))
            assertEquals(2, footballOnly.items.single().topics.size)
            val customOnly = repository.page(filter.copy(topicKey = "other", customTopicLabel = " шахматы ", peopleMin = 1))
            assertEquals(listOf(first), customOnly.items.map(PointRead::id))
            assertEquals(first, repository.detail(first)?.id)
            assertNull(repository.detail(UUID.randomUUID()))
            val quota = repository.quota(actor)
            assertEquals(1, quota.weekUsed)
            assertEquals(1, quota.monthUsed)
            assertTrue(repository.creationContext(actor, baseLat, baseLon).nearby.any { preview -> preview.id == first })
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement -> statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate() }
    }

    private fun seedPoint(connection: Connection, id: UUID, actor: UUID, lon: Double, lat: Double, daysAgo: Int) {
        connection.prepareStatement(
            """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
               VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),'Test address','MANUAL',?,
                       clock_timestamp() - (?::integer * interval '1 day'),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id); statement.setDouble(2, lon); statement.setDouble(3, lat)
            statement.setObject(4, actor); statement.setInt(5, daysAgo); statement.executeUpdate()
        }
    }

    private fun seedTopic(connection: Connection, id: UUID, chatId: UUID, pointId: UUID, key: String, custom: String?) {
        connection.prepareStatement(
            "INSERT INTO point_topics (id,point_id,preset_key,custom_label,created_at) VALUES (?,?,?,?,clock_timestamp())"
        ).use { statement ->
            statement.setObject(1, id); statement.setObject(2, pointId); statement.setString(3, key)
            statement.setString(4, custom); statement.executeUpdate()
        }
        sql(connection, "INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chatId, id)
    }

    private fun sql(connection: Connection, statementSql: String, vararg values: UUID) {
        connection.prepareStatement(statementSql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
}
