package com.example.meetings

import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Projection shared by proposal and booking writes inside the caller's transaction. */
class MeetingProjection {
    /** One projection query plus one overlap query for an entire page. */
    fun readMany(connection: Connection, ids: List<UUID>, viewerId: UUID): Map<UUID, ProposalView> {
        if (ids.isEmpty()) return emptyMap()
        val idArray = connection.createArrayOf("uuid", ids.toTypedArray())
        val views = try {
            connection.prepareStatement(
                """SELECT p.id,p.chat_id,p.proposer_user_id,p.starts_at,p.ends_at,p.lifecycle,p.revision,
                          p.created_at,p.updated_at,clock_timestamp() AS server_now,
                          (SELECT count(*) FROM votes WHERE proposal_id=p.id) AS going_count,
                          EXISTS(SELECT 1 FROM votes WHERE proposal_id=p.id AND user_id=?) AS viewer_going,
                          b.id AS booking_id,b.booked_by_user_id,b.booking_order,b.created_at AS booked_at,
                          b.status AS booking_status,b.cancelled_at,b.cancel_reason
                   FROM meeting_proposals AS p
                   LEFT JOIN LATERAL (
                       SELECT * FROM bookings WHERE proposal_id=p.id
                       ORDER BY (status='ACTIVE') DESC,booking_order DESC LIMIT 1
                   ) AS b ON true
                   WHERE p.id=ANY (?)"""
            ).use { statement ->
                statement.setObject(1, viewerId)
                statement.setArray(2, idArray)
                statement.executeQuery().use { rows -> buildMap {
                    while (rows.next()) {
                        val proposalId = rows.getObject("id", UUID::class.java)
                        val startsAt = rows.getObject("starts_at", OffsetDateTime::class.java).toInstant()
                        val endsAt = rows.getObject("ends_at", OffsetDateTime::class.java).toInstant()
                        val bookingId = rows.getObject("booking_id", UUID::class.java)
                        val bookingStatus = rows.getString("booking_status")
                        val booking = bookingId?.let {
                            BookingView(it, proposalId, rows.getObject("booked_by_user_id", UUID::class.java),
                                bookingStatus.lowercase(), rows.getLong("booking_order"), emptyList(),
                                rows.getObject("booked_at", OffsetDateTime::class.java).toInstant(),
                                rows.getObject("cancelled_at", OffsetDateTime::class.java)?.toInstant(),
                                rows.getString("cancel_reason")?.lowercase())
                        }
                        val now = rows.getObject("server_now", OffsetDateTime::class.java).toInstant()
                        val count = rows.getInt("going_count")
                        val status = when {
                            bookingStatus == "ACTIVE" -> "booked"
                            rows.getString("lifecycle") == "CLOSED" || !startsAt.isAfter(now) -> "closed"
                            count > 1 -> "ready_to_book"
                            else -> "voting"
                        }
                        put(proposalId, ProposalView(proposalId, rows.getObject("chat_id", UUID::class.java),
                            rows.getObject("proposer_user_id", UUID::class.java), startsAt, endsAt, status,
                            count, rows.getBoolean("viewer_going"), booking, rows.getLong("revision"),
                            rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                            rows.getObject("updated_at", OffsetDateTime::class.java).toInstant()))
                    }
                } }
            }
        } finally {
            idArray.free()
        }
        val activeIds = views.values.mapNotNull { it.booking?.takeIf { booking -> booking.status == "active" }?.id }
        if (activeIds.isEmpty()) return views
        val overlapsById = batchOverlaps(connection, activeIds)
        return views.mapValues { (_, proposal) ->
            val booking = proposal.booking
            if (booking == null || booking.status != "active") proposal
            else proposal.copy(booking = booking.copy(overlappingActive = overlapsById[booking.id].orEmpty()))
        }
    }

    fun read(connection: Connection, proposalId: UUID, viewerId: UUID): ProposalView? = connection.prepareStatement(
        """SELECT p.id,p.chat_id,p.proposer_user_id,p.starts_at,p.ends_at,p.lifecycle,p.revision,
                  p.created_at,p.updated_at,pt.point_id,clock_timestamp() AS server_now,
                  (SELECT count(*) FROM votes WHERE proposal_id=p.id) AS going_count,
                  EXISTS(SELECT 1 FROM votes WHERE proposal_id=p.id AND user_id=?) AS viewer_going,
                  b.id AS booking_id,b.booked_by_user_id,b.booking_order,b.created_at AS booked_at,
                  b.status AS booking_status,b.cancelled_at,b.cancel_reason
           FROM meeting_proposals AS p
           JOIN chats AS c ON c.id=p.chat_id
           JOIN point_topics AS pt ON pt.id=c.point_topic_id
           LEFT JOIN LATERAL (
               SELECT * FROM bookings WHERE proposal_id=p.id
               ORDER BY (status='ACTIVE') DESC,booking_order DESC LIMIT 1
           ) AS b ON true
           WHERE p.id=?"""
    ).use { statement ->
        statement.setObject(1, viewerId)
        statement.setObject(2, proposalId)
        statement.executeQuery().use result@{ rows ->
            if (!rows.next()) return@result null
            val chatId = rows.getObject("chat_id", UUID::class.java)
            val pointId = rows.getObject("point_id", UUID::class.java)
            val startsAt = rows.getObject("starts_at", OffsetDateTime::class.java).toInstant()
            val endsAt = rows.getObject("ends_at", OffsetDateTime::class.java).toInstant()
            val count = rows.getInt("going_count")
            val selectedBookingId = rows.getObject("booking_id", UUID::class.java)
            val bookingStatus = rows.getString("booking_status")
            val booking = selectedBookingId?.let {
                BookingView(it, proposalId, rows.getObject("booked_by_user_id", UUID::class.java),
                    bookingStatus.lowercase(), rows.getLong("booking_order"),
                    if (bookingStatus == "ACTIVE") overlaps(connection, it, pointId, startsAt, endsAt) else emptyList(),
                    rows.getObject("booked_at", OffsetDateTime::class.java).toInstant(),
                    rows.getObject("cancelled_at", OffsetDateTime::class.java)?.toInstant(),
                    rows.getString("cancel_reason")?.lowercase())
            }
            val now = rows.getObject("server_now", OffsetDateTime::class.java).toInstant()
            val status = when {
                bookingStatus == "ACTIVE" -> "booked"
                rows.getString("lifecycle") == "CLOSED" || !startsAt.isAfter(now) -> "closed"
                count > 1 -> "ready_to_book"
                else -> "voting"
            }
            ProposalView(proposalId, chatId, rows.getObject("proposer_user_id", UUID::class.java),
                startsAt, endsAt, status, count, rows.getBoolean("viewer_going"), booking,
                rows.getLong("revision"), rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                rows.getObject("updated_at", OffsetDateTime::class.java).toInstant())
        }
    }

    private fun overlaps(connection: Connection, bookingId: UUID, pointId: UUID,
                         startsAt: Instant, endsAt: Instant): List<BookingConflictView> =
        connection.prepareStatement(
            """SELECT b.id,b.proposal_id,p.starts_at,p.ends_at,b.created_at,b.booking_order
               FROM bookings AS b
               JOIN meeting_proposals AS p ON p.id=b.proposal_id
               JOIN chats AS c ON c.id=p.chat_id
               JOIN point_topics AS pt ON pt.id=c.point_topic_id
               WHERE b.status='ACTIVE' AND b.id<>? AND pt.point_id=?
                 AND p.starts_at<? AND p.ends_at>?
               ORDER BY b.booking_order"""
        ).use { statement ->
            statement.setObject(1, bookingId)
            statement.setObject(2, pointId)
            statement.setObject(3, endsAt.atOffset(java.time.ZoneOffset.UTC))
            statement.setObject(4, startsAt.atOffset(java.time.ZoneOffset.UTC))
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(BookingConflictView(
                    rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java),
                    rows.getObject(3, OffsetDateTime::class.java).toInstant(),
                    rows.getObject(4, OffsetDateTime::class.java).toInstant(),
                    rows.getObject(5, OffsetDateTime::class.java).toInstant(), rows.getLong(6),
                ))
            } }
        }

    private fun batchOverlaps(connection: Connection, baseIds: List<UUID>): Map<UUID, List<BookingConflictView>> {
        val idArray = connection.createArrayOf("uuid", baseIds.toTypedArray())
        return try {
            connection.prepareStatement(
                """SELECT base.id AS base_id,other.id,other.proposal_id,other_proposal.starts_at,
                          other_proposal.ends_at,other.created_at,other.booking_order
                   FROM bookings AS base
                   JOIN meeting_proposals AS base_proposal ON base_proposal.id=base.proposal_id
                   JOIN chats AS base_chat ON base_chat.id=base_proposal.chat_id
                   JOIN point_topics AS base_topic ON base_topic.id=base_chat.point_topic_id
                   JOIN point_topics AS other_topic ON other_topic.point_id=base_topic.point_id
                   JOIN chats AS other_chat ON other_chat.point_topic_id=other_topic.id
                   JOIN meeting_proposals AS other_proposal ON other_proposal.chat_id=other_chat.id
                   JOIN bookings AS other ON other.proposal_id=other_proposal.id AND other.status='ACTIVE'
                   WHERE base.id=ANY (?) AND other.id<>base.id
                     AND other_proposal.starts_at<base_proposal.ends_at
                     AND other_proposal.ends_at>base_proposal.starts_at
                   ORDER BY base.id,other.booking_order"""
            ).use { statement ->
                statement.setArray(1, idArray)
                statement.executeQuery().use { rows -> buildMap<UUID, MutableList<BookingConflictView>> {
                    while (rows.next()) {
                        val baseId = rows.getObject(1, UUID::class.java)
                        getOrPut(baseId) { mutableListOf() }.add(BookingConflictView(
                            rows.getObject(2, UUID::class.java), rows.getObject(3, UUID::class.java),
                            rows.getObject(4, OffsetDateTime::class.java).toInstant(),
                            rows.getObject(5, OffsetDateTime::class.java).toInstant(),
                            rows.getObject(6, OffsetDateTime::class.java).toInstant(), rows.getLong(7),
                        ))
                    }
                } }
            }
        } finally {
            idArray.free()
        }
    }
}
