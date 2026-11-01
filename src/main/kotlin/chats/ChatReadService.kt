package com.example.chats

import com.example.persistence.DatabaseRuntime
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ChatReadService(private val database: DatabaseRuntime, cursorSecret: String) {
    private val cursors = ChatCursorCodec(cursorSecret)

    suspend fun myChats(userId: UUID, cursor: String? = null, limit: Int = 30): ChatPageView {
        require(limit in 1..100)
        val fingerprint = digest("my-chats:$userId:$limit")
        val after = cursor?.let { cursors.decode("my-chats", fingerprint, it) }
        require(after?.third == null) { "Invalid cursor" }
        return database.withReadOnlyJdbcTransaction { connection ->
            val heads = connection.prepareStatement(
                """SELECT c.id,c.created_at FROM chat_memberships AS m
                   JOIN chats AS c ON c.id=m.chat_id
                   WHERE m.user_id=? AND m.left_at IS NULL
                     AND (?::timestamptz IS NULL OR (c.created_at,c.id) < (?,?))
                   ORDER BY c.created_at DESC,c.id DESC LIMIT ?"""
            ).use { statement ->
                statement.setObject(1, userId)
                val at = after?.first?.atOffset(ZoneOffset.UTC)
                statement.setObject(2, at)
                statement.setObject(3, at)
                statement.setObject(4, after?.second)
                statement.setInt(5, limit + 1)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getObject(2, OffsetDateTime::class.java).toInstant())
                } }
            }
            val page = heads.take(limit)
            val details = readSummaries(connection, userId, page.map { it.first })
            ChatPageView(page.mapNotNull { details[it.first]?.summary },
                if (heads.size > limit) page.lastOrNull()?.let { cursors.encode("my-chats", fingerprint, it.second, it.first) }
                else null)
        }
    }

    suspend fun detail(userId: UUID, chatId: UUID): ChatDetailView? = database.withReadOnlyJdbcTransaction { connection ->
        readSummaries(connection, userId, listOf(chatId))[chatId]
    }

    suspend fun history(userId: UUID, chatId: UUID, beforeSeq: Long? = null, afterSeq: Long? = null,
                        limit: Int = 30): MessageHistoryView? {
        require(limit in 1..100 && (beforeSeq == null || beforeSeq > 0) && (afterSeq == null || afterSeq >= 0))
        require(beforeSeq == null || afterSeq == null) { "beforeSeq and afterSeq are mutually exclusive" }
        return database.withReadOnlyJdbcTransaction { connection ->
            if (!isActiveMember(connection, userId, chatId)) return@withReadOnlyJdbcTransaction null
            val direction = if (afterSeq != null) "ASC" else "DESC"
            val comparator = when { afterSeq != null -> "AND msg.message_seq > ?"; beforeSeq != null -> "AND msg.message_seq < ?"; else -> "" }
            val rows = connection.prepareStatement(
                """SELECT msg.id,msg.chat_id,msg.message_seq,msg.type,msg.author_user_id,u.display_name,u.avatar_object_key,
                          msg.body,msg.system_type,msg.system_payload,msg.created_at
                   FROM messages AS msg LEFT JOIN users AS u ON u.id=msg.author_user_id AND u.deleted_at IS NULL
                   WHERE msg.chat_id=? $comparator ORDER BY msg.message_seq $direction LIMIT ?"""
            ).use { statement ->
                statement.setObject(1, chatId)
                val hasSeq = afterSeq != null || beforeSeq != null
                if (hasSeq) statement.setLong(2, afterSeq ?: beforeSeq!!)
                statement.setInt(if (hasSeq) 3 else 2, limit + 1)
                statement.executeQuery().use { results -> buildList {
                    while (results.next()) add(message(results))
                } }
            }
            val page = rows.take(limit)
            MessageHistoryView(page,
                if (afterSeq == null && rows.size > limit) page.lastOrNull()?.messageSeq else null,
                if (afterSeq != null && rows.size > limit) page.lastOrNull()?.messageSeq else null)
        }
    }

    suspend fun search(userId: UUID, chatId: UUID, q: String, cursor: String? = null,
                       limit: Int = 30): MessageSearchView? {
        val query = q.trim()
        require(query.codePointCount(0, query.length) in 3..256) { "Search query must have 3..256 characters" }
        require(limit in 1..100)
        val fingerprint = digest("search:$userId:$chatId:$limit:$query")
        val after = cursor?.let { cursors.decode("search", fingerprint, it) }
        require(after == null || after.third != null) { "Invalid cursor" }
        return database.withReadOnlyJdbcTransaction { connection ->
            if (!isActiveMember(connection, userId, chatId)) return@withReadOnlyJdbcTransaction null
            val upper = after?.third ?: connection.prepareStatement(
                "SELECT last_message_seq FROM chats WHERE id=?"
            ).use { statement ->
                statement.setObject(1, chatId)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
            }
            val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val rows = connection.prepareStatement(
                """SELECT msg.id,msg.chat_id,msg.message_seq,msg.type,msg.author_user_id,u.display_name,u.avatar_object_key,
                          msg.body,msg.system_type,msg.system_payload,msg.created_at
                   FROM messages AS msg LEFT JOIN users AS u ON u.id=msg.author_user_id AND u.deleted_at IS NULL
                   WHERE msg.chat_id=? AND msg.type='TEXT' AND msg.message_seq<=?
                     AND msg.body ILIKE ? ESCAPE '\'
                     AND (?::timestamptz IS NULL OR (msg.created_at,msg.id) < (?,?))
                   ORDER BY msg.created_at DESC,msg.id DESC LIMIT ?"""
            ).use { statement ->
                statement.setObject(1, chatId)
                statement.setLong(2, upper)
                statement.setString(3, pattern)
                val at = after?.first?.atOffset(ZoneOffset.UTC)
                statement.setObject(4, at)
                statement.setObject(5, at)
                statement.setObject(6, after?.second)
                statement.setInt(7, limit + 1)
                statement.executeQuery().use { results -> buildList {
                    while (results.next()) add(message(results))
                } }
            }
            val page = rows.take(limit)
            MessageSearchView(page, if (rows.size > limit) page.lastOrNull()?.let {
                cursors.encode("search", fingerprint, it.createdAt, it.id, upper)
            } else null)
        }
    }

    suspend fun advanceReadCursor(userId: UUID, chatId: UUID, seq: Long): ReadCursorResult {
        require(seq >= 0)
        return database.withRetryingJdbcTransaction { connection ->
            connection.prepareStatement("SELECT id FROM users WHERE id=? AND deleted_at IS NULL FOR NO KEY UPDATE").use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rows -> check(rows.next()) { "Active user required" } }
            }
            val last = connection.prepareStatement("SELECT last_message_seq FROM chats WHERE id=? FOR UPDATE").use { statement ->
                statement.setObject(1, chatId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            } ?: return@withRetryingJdbcTransaction ReadCursorResult.NotMember
            if (!isActiveMember(connection, userId, chatId)) {
                return@withRetryingJdbcTransaction ReadCursorResult.NotMember
            }
            if (seq > last) return@withRetryingJdbcTransaction ReadCursorResult.BeyondLastMessage
            connection.prepareStatement(
                """UPDATE chat_memberships SET last_read_seq=GREATEST(last_read_seq,?)
                   WHERE chat_id=? AND user_id=? AND left_at IS NULL RETURNING joined_at,last_read_seq"""
            ).use { statement ->
                statement.setLong(1, seq)
                statement.setObject(2, chatId)
                statement.setObject(3, userId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) ReadCursorResult.NotMember else ReadCursorResult.Updated(
                        MembershipView(chatId, userId, rows.getObject(1, OffsetDateTime::class.java).toInstant(), rows.getLong(2)))
                }
            }
        }
    }

    private fun isActiveMember(connection: Connection, userId: UUID, chatId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM chat_memberships WHERE user_id=? AND chat_id=? AND left_at IS NULL"
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, chatId)
        statement.executeQuery().use { it.next() }
    }

    private fun readSummaries(connection: Connection, userId: UUID, ids: List<UUID>): Map<UUID, ChatDetailView> {
        if (ids.isEmpty()) return emptyMap()
        val array = connection.createArrayOf("uuid", ids.toTypedArray())
        try {
            return connection.prepareStatement(
                """SELECT c.id,pt.point_id,pt.id,pt.preset_key,coalesce(pt.custom_label,preset.label_ru),
                          c.participant_count,p.address,p.updated_at,m.joined_at,m.last_read_seq,c.last_message_seq,
                          last_msg.id,last_msg.type,last_msg.body,last_msg.system_type,last_msg.created_at,
                          (SELECT count(*) FROM messages AS unread WHERE unread.chat_id=c.id AND unread.message_seq>m.last_read_seq),
                          proposal.id,proposal.starts_at,proposal.ends_at,proposal.going_count,proposal.booking_id,proposal.revision
                   FROM chats AS c
                   JOIN point_topics AS pt ON pt.id=c.point_topic_id
                   JOIN topic_presets AS preset ON preset.key=pt.preset_key
                   JOIN points AS p ON p.id=pt.point_id
                   JOIN chat_memberships AS m ON m.chat_id=c.id AND m.user_id=? AND m.left_at IS NULL
                   LEFT JOIN LATERAL (
                       SELECT msg.id,msg.type,msg.body,msg.system_type,msg.created_at FROM messages AS msg
                       WHERE msg.chat_id=c.id ORDER BY msg.message_seq DESC LIMIT 1
                   ) AS last_msg ON true
                   LEFT JOIN LATERAL (
                       SELECT prop.id,prop.starts_at,prop.ends_at,prop.revision,
                              (SELECT count(*) FROM votes AS v WHERE v.proposal_id=prop.id) AS going_count,
                              (SELECT b.id FROM bookings AS b WHERE b.proposal_id=prop.id AND b.status='ACTIVE') AS booking_id
                       FROM meeting_proposals AS prop
                       WHERE prop.chat_id=c.id AND prop.lifecycle='OPEN' AND prop.starts_at>clock_timestamp()
                       ORDER BY prop.starts_at,prop.id LIMIT 1
                   ) AS proposal ON true
                   WHERE c.id=ANY (?)"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setArray(2, array)
                statement.executeQuery().use { rows -> buildMap {
                    while (rows.next()) {
                        val id = rows.getObject(1, UUID::class.java)
                        val count = rows.getInt(6)
                        val topic = ChatTopicView(rows.getObject(3, UUID::class.java), rows.getString(4),
                            rows.getString(5), count, id)
                        val lastId = rows.getObject(12, UUID::class.java)
                        val last = lastId?.let { MessagePreviewView(it, rows.getString(13).lowercase(),
                            if (rows.getString(13) == "TEXT") rows.getString(14) else systemPreview(rows.getString(15)),
                            rows.getObject(16, OffsetDateTime::class.java).toInstant()) }
                        val proposalId = rows.getObject(18, UUID::class.java)
                        val proposal = proposalId?.let {
                            val booking = rows.getObject(22, UUID::class.java)
                            val votes = rows.getInt(21)
                            ProposalPreviewView(it, rows.getObject(19, OffsetDateTime::class.java).toInstant(),
                                rows.getObject(20, OffsetDateTime::class.java).toInstant(),
                                if (booking != null) "booked" else if (votes > 1) "ready_to_book" else "voting",
                                votes, booking, rows.getLong(23))
                        }
                        val summary = ChatSummaryView(id, rows.getObject(2, UUID::class.java), topic,
                            rows.getString(7), count, last, rows.getLong(17), proposal,
                            rows.getObject(8, OffsetDateTime::class.java).toInstant())
                        put(id, ChatDetailView(summary, MembershipView(id, userId,
                            rows.getObject(9, OffsetDateTime::class.java).toInstant(), rows.getLong(10)), rows.getLong(11)))
                    }
                } }
            }
        } finally { array.free() }
    }

    private fun message(rows: ResultSet): MessageView = MessageView(
        rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getLong(3), rows.getString(4),
        rows.getObject(5, UUID::class.java), rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9),
        rows.getString(10)?.let { Json.parseToJsonElement(it) as JsonObject },
        rows.getObject(11, OffsetDateTime::class.java).toInstant(),
    )

    private fun systemPreview(kind: String?): String = when (kind) {
        "member_joined" -> "Участник присоединился"
        "member_left" -> "Участник вышел"
        "meeting_proposed" -> "Предложена встреча"
        "meeting_ready" -> "Встреча готова к бронированию"
        "meeting_booked" -> "Встреча забронирована"
        "meeting_booking_cancelled" -> "Бронь встречи отменена"
        "meeting_closed" -> "Встреча закрыта"
        "point_deleted" -> "Точка удалена"
        else -> "Событие чата"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
