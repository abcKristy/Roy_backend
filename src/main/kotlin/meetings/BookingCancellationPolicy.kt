package com.example.meetings

import com.example.persistence.requireTransaction
import java.sql.Connection
import java.util.UUID

/** Recalculates after vote loss inside the caller's actor/point/proposal locks and transaction. */
class BookingCancellationPolicy {
    /** Returns only transitions performed here as (booking ID, proposal ID); history and side effects stay with callers. */
    fun cancelInsufficientBookings(connection: Connection, proposalIds: List<UUID>): List<Pair<UUID, UUID>> {
        requireTransaction(connection)
        if (proposalIds.isEmpty()) return emptyList()
        val array = connection.createArrayOf("uuid", proposalIds.toTypedArray())
        return try {
            connection.prepareStatement(
                """UPDATE bookings AS b SET status='CANCELLED',cancelled_at=clock_timestamp(),
                   cancel_reason='INSUFFICIENT_VOTES'
                   WHERE b.proposal_id=ANY(?) AND b.status='ACTIVE'
                   AND (SELECT count(*) FROM votes v WHERE v.proposal_id=b.proposal_id)<=1
                   RETURNING b.id,b.proposal_id"""
            ).use { statement ->
                statement.setArray(1, array)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getObject(2, UUID::class.java))
                } }
            }
        } finally { array.free() }
    }
}
