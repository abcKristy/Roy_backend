package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M2ExactNearbyBoundaryIntegrationTest {
    @Test
    fun `geography radius includes exact projected 100 metres and excludes just outside`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_radius_boundary$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val ids = listOf(0.0, 99.999, 100.0, 100.001).associateWith { UUID.randomUUID() }
        val lat = ThreadLocalRandom.current().nextDouble(-30.0, -20.0)
        val lon = ThreadLocalRandom.current().nextDouble(120.0, 130.0)
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
            connection.prepareStatement(
                """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                   VALUES (?,?,'synthetic-unusable-hash','Radius actor',20,'fixture-only',
                           clock_timestamp(),clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, actor)
                statement.setString(2, phone)
                assertEquals(1, statement.executeUpdate())
            }
            for ((distance, id) in ids) connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_Project(ST_SetSRID(ST_MakePoint(?,?),4326)::geography,?,radians(90))::geometry,
                           ?,'MANUAL',?,clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, id)
                statement.setDouble(2, lon)
                statement.setDouble(3, lat)
                statement.setDouble(4, distance)
                statement.setString(5, "Radius $distance")
                statement.setObject(6, actor)
                assertEquals(1, statement.executeUpdate())
            }
            connection.commit()
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "radius-test-secret",
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val result = PointReadRepository(runtime, "radius-cursor-secret-at-least-32-bytes")
                .creationContext(actor, lat, lon)
            assertEquals(ids.filterKeys { it <= 100.0 }.values.toSet(), result.nearby.map { it.id }.toSet())
        }
        DriverManager.getConnection(url, user, password).use { connection ->
            for ((expectedDistance, id) in ids) connection.prepareStatement(
                """WITH center AS (SELECT ST_SetSRID(ST_MakePoint(?,?),4326)::geography AS g)
                   SELECT ST_Distance(p.location::geography,c.g),
                          ST_DWithin(p.location::geography,c.g,100.01),
                          ST_Distance(p.location::geography,c.g)<=100
                   FROM points p CROSS JOIN center c WHERE p.id=?"""
            ).use { statement ->
                statement.setDouble(1, lon)
                statement.setDouble(2, lat)
                statement.setObject(3, id)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(kotlin.math.abs(rows.getDouble(1) - expectedDistance) < 0.0001,
                        "Projected $expectedDistance m location must retain metre precision")
                    // The widened index prefilter includes both sides of the boundary;
                    // the exact distance comparison decides the public 100 m contract.
                    assertTrue(rows.getBoolean(2))
                    if (expectedDistance <= 100.0) assertTrue(rows.getBoolean(3))
                    else assertFalse(rows.getBoolean(3))
                }
            }
        }
    }

    @Test
    fun `create requires confirmation at exact 100 metres but allows just outside`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_radius_boundary$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val owner = UUID.randomUUID()
        val exactActor = UUID.randomUUID()
        val outsideActor = UUID.randomUUID()
        val anchor = UUID.randomUUID()
        val lat = ThreadLocalRandom.current().nextDouble(-30.0, -20.0)
        val lon = ThreadLocalRandom.current().nextDouble(120.0, 130.0)
        val positions = mutableMapOf<Double, PointPosition>()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            for (actor in listOf(owner, exactActor, outsideActor)) {
                val phone = "+7" + actor.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                connection.prepareStatement(
                    """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                       accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                       VALUES (?,?,'synthetic-unusable-hash','Radius actor',20,'fixture-only',
                               clock_timestamp(),clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, actor)
                    statement.setString(2, phone)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
                    .use { statement -> statement.setObject(1, actor); assertEquals(1, statement.executeUpdate()) }
            }
            connection.prepareStatement(
                """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),'Create radius anchor','MANUAL',?,
                           clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, anchor)
                statement.setDouble(2, lon)
                statement.setDouble(3, lat)
                statement.setObject(4, owner)
                assertEquals(1, statement.executeUpdate())
            }
            for (metres in listOf(100.0, 100.001)) connection.prepareStatement(
                """SELECT ST_Y(g),ST_X(g) FROM
                   (SELECT ST_Project(ST_SetSRID(ST_MakePoint(?,?),4326)::geography,?,radians(90))::geometry AS g) AS v"""
            ).use { statement ->
                statement.setDouble(1, lon)
                statement.setDouble(2, lat)
                statement.setDouble(3, metres)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    positions[metres] = PointPosition(rows.getDouble(1), rows.getDouble(2))
                }
            }
            connection.commit()
        }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "radius-create-test-secret",
            appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val write = PointWriteService(runtime)
            fun command(actor: UUID, metres: Double) = CreatePointCommand(actor, UUID.randomUUID(),
                positions.getValue(metres), PointTopicInput("football"), "Create radius $metres",
                PointAddressSource.MANUAL, false)
            val pending = assertIs<CreatePointResult.NearbyConfirmationRequired>(write.create(
                command(exactActor, 100.0),
                { _, created -> buildJsonObject { put("pointId", created.pointId.toString()) } },
                { buildJsonObject { put("blockedUntil", it.toString()) } },
            ))
            assertTrue(pending.nearby.any { it.id == anchor })
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(0, countActor(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", exactActor))
                assertEquals(0, countActor(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", exactActor))
                assertEquals(0, countActor(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", exactActor))
            }
            val created = assertIs<CreatePointResult.Created>(write.create(
                command(outsideActor, 100.001),
                { _, value -> buildJsonObject { put("pointId", value.pointId.toString()) } },
                { buildJsonObject { put("blockedUntil", it.toString()) } },
            ))
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(1, countActor(connection, "SELECT count(*) FROM points WHERE created_by_user_id=?", outsideActor))
                assertEquals(1, countActor(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id=?", outsideActor))
                assertEquals(1, countActor(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id=?", outsideActor))
                assertEquals(1, countActor(connection, "SELECT count(*) FROM point_topics WHERE point_id=?", created.value.pointId))
                assertEquals(1, countActor(connection, "SELECT count(*) FROM chats WHERE id=? AND participant_count=1", created.value.chatId))
                connection.prepareStatement(
                    "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND user_id=? AND left_at IS NULL"
                ).use { statement ->
                    statement.setObject(1, created.value.chatId)
                    statement.setObject(2, outsideActor)
                    statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
                }
            }
        }
    }

    private fun countActor(connection: Connection, sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
