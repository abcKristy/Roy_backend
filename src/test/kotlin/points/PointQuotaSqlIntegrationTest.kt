package com.example.points

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointQuotaSqlIntegrationTest {
    @Test
    fun `rolling windows include their lower boundary and exclude now and future`() {
        val url = System.getenv("TEST_DATABASE_URL") ?: return
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t2_ledger$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actorId = UUID.randomUUID()
        val at = Instant.parse("2026-11-03T12:00:00Z")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actorId)
            insertEvent(connection, actorId, at.minus(7, ChronoUnit.DAYS))
            insertEvent(connection, actorId, at.minus(7, ChronoUnit.DAYS).minusSeconds(1))
            insertEvent(connection, actorId, at.minus(30, ChronoUnit.DAYS))
            insertEvent(connection, actorId, at.minus(30, ChronoUnit.DAYS).minusSeconds(1))
            val atEvent = insertEvent(connection, actorId, at)
            val futureEvent = insertEvent(connection, actorId, at.plusSeconds(1))
            connection.commit()

            val initial = PointQuotaSql.read(connection, actorId, at)
            assertEquals(1, initial.weekUsed)
            assertEquals(3, initial.monthUsed)
            assertEquals(2, initial.weekRemaining)
            assertEquals(7, initial.monthRemaining)
            assertNull(initial.weekNextAvailableAt)
            assertNull(initial.monthNextAvailableAt)
            assertFalse(initial.lastAvailableWarning)

            connection.autoCommit = false
            connection.prepareStatement("DELETE FROM point_creation_events WHERE id IN (?,?)").use { statement ->
                statement.setObject(1, atEvent)
                statement.setObject(2, futureEvent)
                assertEquals(2, statement.executeUpdate())
            }
            insertEvent(connection, actorId, at.minus(1, ChronoUnit.DAYS))
            insertEvent(connection, actorId, at.minus(2, ChronoUnit.DAYS))
            repeat(5) { index -> insertEvent(connection, actorId, at.minus(10L + index, ChronoUnit.DAYS)) }
            connection.commit()

            val full = PointQuotaSql.read(connection, actorId, at)
            assertEquals(3, full.weekUsed)
            assertEquals(10, full.monthUsed)
            assertEquals(0, full.weekRemaining)
            assertEquals(0, full.monthRemaining)
            assertEquals(at, full.weekNextAvailableAt)
            assertEquals(at, full.monthNextAvailableAt)
            assertFalse(full.lastAvailableWarning)

            val afterBoundary = PointQuotaSql.read(connection, actorId, at.plusSeconds(1))
            assertEquals(2, afterBoundary.weekUsed)
            assertEquals(9, afterBoundary.monthUsed)
            assertEquals(1, afterBoundary.weekRemaining)
            assertEquals(1, afterBoundary.monthRemaining)
            assertNull(afterBoundary.weekNextAvailableAt)
            assertNull(afterBoundary.monthNextAvailableAt)
            assertTrue(afterBoundary.lastAvailableWarning)
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

    private fun insertEvent(connection: Connection, actorId: UUID, at: Instant): UUID {
        val id = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO point_creation_events (id,creator_user_id,created_at) VALUES (?,?,?)"
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, actorId)
            statement.setObject(3, at.atOffset(java.time.ZoneOffset.UTC))
            assertEquals(1, statement.executeUpdate())
        }
        return id
    }
}
