package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointNearbyQueryIntegrationTest {
    @Test
    fun `nearby uses geography metres instead of a bounding box`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t2_nearby$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        val center = UUID.randomUUID()
        val east = UUID.randomUUID()
        val north = UUID.randomUUID()
        val outside = UUID.randomUUID()
        val lon = -110.0 + Math.random() * 10.0
        val lat = -0.1 + Math.random() * 0.2
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actor)
            insertPoint(connection, center, actor, lon, lat, 0.0)
            // Near the equator the sphere ranks the 99.4 m east point ahead of the 99 m north point.
            insertPoint(connection, east, actor, lon, lat, 99.4)
            insertPoint(connection, north, actor, lon, lat, 99.0, 0.0)
            insertPoint(connection, outside, actor, lon, lat, 101.0)
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION))
            .start().use { runtime ->
                val repository = PointReadRepository(runtime, "test-cursor-secret-at-least-thirty-two-bytes")
                val context = repository.creationContext(actor, lat, lon)
                assertEquals(listOf(center, north, east), context.nearby.map { it.id })
                assertEquals(0, context.nearby.single { it.id == center }.distanceMeters)
                assertEquals(99, context.nearby.single { it.id == east }.distanceMeters)
                assertEquals(99, context.nearby.single { it.id == north }.distanceMeters)
                assertEquals(lat, context.nearby.single { it.id == center }.lat)
                assertEquals(lon, context.nearby.single { it.id == center }.lon)
                assertEquals("Nearby fixture", context.nearby.single { it.id == east }.address)
                assertEquals(0, context.quota.weekUsed)
            }

        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                "SELECT ST_Contains(ST_MakeEnvelope(?,?,?,?,4326),location) FROM points WHERE id=?"
            ).use { statement ->
                statement.setDouble(1, lon - 0.002)
                statement.setDouble(2, lat - 0.002)
                statement.setDouble(3, lon + 0.002)
                statement.setDouble(4, lat + 0.002)
                statement.setObject(5, outside)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBoolean(1), "The 101 m point is still inside the bbox")
                }
            }
            connection.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname='points_location_geography_gist_idx'"
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getString(1).contains("USING gist"))
                    assertTrue(rows.getString(1).contains("location)::geography"))
                    assertFalse(rows.next())
                }
            }
        }
    }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, phone)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertPoint(connection: Connection, id: UUID, actor: UUID, lon: Double, lat: Double,
                            metres: Double, bearingDegrees: Double = 90.0) {
        connection.prepareStatement(
            """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
               VALUES (?,ST_Project(ST_SetSRID(ST_MakePoint(?,?),4326)::geography,?,radians(?))::geometry,
                       'Nearby fixture','MANUAL',?,clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setDouble(2, lon)
            statement.setDouble(3, lat)
            statement.setDouble(4, metres)
            statement.setDouble(5, bearingDegrees)
            statement.setObject(6, actor)
            assertEquals(1, statement.executeUpdate())
        }
    }
}
