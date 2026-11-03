package com.example.points

import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M2ServerFixturePassportIntegrationTest {
    @Test
    fun `fixed M2 fixture has independent time distance and membership expectations`() {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t6_fixture$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val anchorUser = UUID.fromString("50000000-0000-4000-8000-000000000001")
        val anchor = UUID.fromString("60000000-0000-4000-8000-000000000001")
        val inside = UUID.fromString("60000000-0000-4000-8000-000000000002")
        val outside = UUID.fromString("60000000-0000-4000-8000-000000000003")
        val at = Instant.parse("2026-11-03T12:00:00Z")

        DriverManager.getConnection(url, user, password).use { connection ->
            connection.isReadOnly = true
            assertEquals("roy_m2t6_fixture", connection.catalog)
            val quota = PointQuotaSql.read(connection, anchorUser, at)
            assertEquals(2, quota.weekUsed)
            assertEquals(4, quota.monthUsed)
            assertEquals(1, quota.weekRemaining)
            assertEquals(6, quota.monthRemaining)
            assertTrue(quota.lastAvailableWarning)

            val expectedEvents = listOf(
                "2026-10-04T11:59:59Z", "2026-10-04T12:00:00Z",
                "2026-10-27T11:59:59Z", "2026-10-27T12:00:00Z",
                "2026-10-27T12:00:01Z", "2026-11-03T12:00:00Z",
            ).map(Instant::parse)
            val actualEvents = connection.prepareStatement(
                "SELECT created_at FROM point_creation_events WHERE creator_user_id=? ORDER BY created_at"
            ).use { statement ->
                statement.setObject(1, anchorUser)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, OffsetDateTime::class.java).toInstant())
                } }
            }
            assertEquals(expectedEvents, actualEvents)

            fun distance(other: UUID): Pair<Double, Boolean> = connection.prepareStatement(
                """SELECT ST_Distance(a.location::geography,b.location::geography),
                          ST_DWithin(a.location::geography,b.location::geography,100)
                   FROM points a JOIN points b ON b.id=? WHERE a.id=?"""
            ).use { statement ->
                statement.setObject(1, other)
                statement.setObject(2, anchor)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getDouble(1) to rows.getBoolean(2)
                }
            }
            val (insideMeters, insideRadius) = distance(inside)
            val (outsideMeters, outsideRadius) = distance(outside)
            assertTrue(insideMeters in 98.9..99.3, "Inside coordinate must be about 99.08 m")
            assertTrue(insideRadius)
            assertTrue(outsideMeters in 101.1..101.5, "Outside coordinate must be about 101.30 m")
            assertFalse(outsideRadius)

            connection.prepareStatement(
                """SELECT count(*) FROM chats c JOIN point_topics t ON t.id=c.point_topic_id
                   WHERE t.point_id IN (?,?,?) AND c.participant_count=1"""
            ).use { statement ->
                statement.setObject(1, anchor)
                statement.setObject(2, inside)
                statement.setObject(3, outside)
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(3, rows.getInt(1)) }
            }
            connection.prepareStatement(
                """SELECT count(*), count(DISTINCT m.user_id)
                   FROM chat_memberships m JOIN chats c ON c.id=m.chat_id
                   JOIN point_topics t ON t.id=c.point_topic_id
                   WHERE t.point_id IN (?,?,?) AND m.left_at IS NULL"""
            ).use { statement ->
                statement.setObject(1, anchor)
                statement.setObject(2, inside)
                statement.setObject(3, outside)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(3, rows.getInt(1))
                    assertEquals(3, rows.getInt(2))
                }
            }
        }
    }
}
