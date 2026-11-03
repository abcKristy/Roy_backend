package com.example.points

import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** One server-clock view of the two rolling windows, shared by reads and create guards. */
internal object PointQuotaSql {
    fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next())
            rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }

    fun read(connection: Connection, userId: UUID, at: Instant): PointQuotaRead = connection.prepareStatement(
        """WITH anchor AS (SELECT ?::timestamptz AS ts)
           SELECT count(*) FILTER (WHERE e.created_at >= anchor.ts - interval '168 hours'
                                      AND e.created_at < anchor.ts),
                  count(*) FILTER (WHERE e.created_at >= anchor.ts - interval '720 hours'
                                      AND e.created_at < anchor.ts),
                  min(e.created_at) FILTER (WHERE e.created_at >= anchor.ts - interval '168 hours'
                                            AND e.created_at < anchor.ts),
                  min(e.created_at) FILTER (WHERE e.created_at >= anchor.ts - interval '720 hours'
                                            AND e.created_at < anchor.ts),
                  u.create_point_blocked_until
           FROM users AS u CROSS JOIN anchor
           LEFT JOIN point_creation_events AS e ON e.creator_user_id = u.id
           WHERE u.id = ? AND u.deleted_at IS NULL
           GROUP BY u.id,u.create_point_blocked_until"""
    ).use { statement ->
        statement.setObject(1, at.atOffset(ZoneOffset.UTC))
        statement.setObject(2, userId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Active user is required" }
            val weekUsed = rows.getInt(1)
            val monthUsed = rows.getInt(2)
            val weekOldest = rows.getObject(3, OffsetDateTime::class.java)?.toInstant()
            val monthOldest = rows.getObject(4, OffsetDateTime::class.java)?.toInstant()
            val blockedUntil = rows.getObject(5, OffsetDateTime::class.java)?.toInstant()?.takeIf { it > at }
            PointQuotaRead(
                weekUsed = weekUsed,
                monthUsed = monthUsed,
                weekNextAvailableAt = if (weekUsed >= 3) weekOldest?.plus(7, ChronoUnit.DAYS) else null,
                monthNextAvailableAt = if (monthUsed >= 10) monthOldest?.plus(30, ChronoUnit.DAYS) else null,
                blockedUntil = blockedUntil,
            )
        }
    }
}
