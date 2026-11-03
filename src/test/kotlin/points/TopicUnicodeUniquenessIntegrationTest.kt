package com.example.points

import com.example.api.PointWriteSnapshot
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TopicUnicodeUniquenessIntegrationTest {
    @Test
    fun `unicode surrounding whitespace and Cyrillic case cannot create duplicate chats`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t3_autojoin$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'test-only','Unicode tester',24,'test-only',now(),now(),now())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, "+79" + actor.leastSignificantBits.toULong().toString().padStart(9, '0').takeLast(9))
                assertEquals(1, statement.executeUpdate())
            }
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only",
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val point = assertIs<CreatePointResult.Created>(service.create(
                CreatePointCommand(actor, UUID.randomUUID(), PointPosition(-60.0, -130.0),
                    PointTopicInput("football"), "Unicode fixture", PointAddressSource.MANUAL, true),
                PointWriteSnapshot::create, { buildJsonObject {} },
            ))
            val label = "\u00a0\tШАХМАТЫ\u2003"
            val created = assertIs<AddTopicResult.Created>(service.addTopic(
                AddTopicCommand(actor, UUID.randomUUID(), point.value.pointId, PointTopicInput("other", label)),
                PointWriteSnapshot::addTopic,
            ))
            assertEquals(AddTopicResult.DuplicateTopic, service.addTopic(
                AddTopicCommand(actor, UUID.randomUUID(), point.value.pointId, PointTopicInput("other", "шахматы")),
                PointWriteSnapshot::addTopic,
            ))
            runtime.withReadOnlyJdbcTransaction { connection ->
                connection.prepareStatement(
                    """SELECT t.custom_label, lower(btrim(t.custom_label)),
                              (SELECT count(*) FROM chats WHERE point_topic_id=t.id),
                              (SELECT count(*) FROM point_topics WHERE point_id=t.point_id AND preset_key='other')
                       FROM point_topics t WHERE t.id=?"""
                ).use { statement ->
                    statement.setObject(1, created.value.topicId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("ШАХМАТЫ", rows.getString(1))
                        assertEquals(PointTopicInput("other", label).normalizeTopic().key.customLabelKey, rows.getString(2))
                        assertEquals(1, rows.getInt(3))
                        assertEquals(1, rows.getInt(4))
                        assertTrue(!rows.next())
                    }
                }
            }
        }
    }
}
