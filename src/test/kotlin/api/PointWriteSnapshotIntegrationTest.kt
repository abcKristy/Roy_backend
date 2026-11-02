package com.example.api

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.AddTopicCommand
import com.example.points.AddTopicResult
import com.example.points.CreatePointCommand
import com.example.points.CreatePointResult
import com.example.points.PointAddressSource
import com.example.points.PointPosition
import com.example.points.PointTopicInput
import com.example.points.PointWriteService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PointWriteSnapshotIntegrationTest {
    @Test
    fun `create and add topic store complete HTTP bodies inside the transaction`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step4snapshot$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'test-hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0'))
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
                .use { statement -> statement.setObject(1, actor); statement.executeUpdate() }
        }
        val lat = ThreadLocalRandom.current().nextDouble(55.0, 56.0)
        val lon = ThreadLocalRandom.current().nextDouble(37.0, 38.0)
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION))
            .start().use { runtime ->
                val service = PointWriteService(runtime)
                val created = assertIs<CreatePointResult.Created>(service.create(
                    CreatePointCommand(actor, UUID.randomUUID(), PointPosition(lat, lon),
                        PointTopicInput("football"), "Тест", PointAddressSource.MANUAL, false),
                    PointWriteSnapshot::create,
                    { until -> buildJsonObject { put("blockedUntil", until.toString()) } },
                ))
                val createBody = assertNotNull(created.responseSnapshot["body"]).jsonObject
                assertEquals(created.value.pointId.toString(), createBody["point"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                assertEquals(created.value.chatId.toString(), createBody["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                assertEquals("1", createBody["quota"]?.jsonObject?.get("weekUsed")?.jsonPrimitive?.content)
                assertNotNull(createBody["chat"]?.jsonObject?.get("lastMessage")?.jsonObject)

                val added = assertIs<AddTopicResult.Created>(service.addTopic(
                    AddTopicCommand(actor, UUID.randomUUID(), created.value.pointId, PointTopicInput("tennis")),
                    PointWriteSnapshot::addTopic,
                ))
                val addBody = assertNotNull(added.responseSnapshot["body"]).jsonObject
                assertEquals(added.value.topicId.toString(), addBody["topic"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                assertEquals(added.value.chatId.toString(), addBody["membership"]?.jsonObject?.get("chatId")?.jsonPrimitive?.content)
                assertEquals("1", addBody["membership"]?.jsonObject?.get("lastReadMessageSeq")?.jsonPrimitive?.content)
            }
    }
}
