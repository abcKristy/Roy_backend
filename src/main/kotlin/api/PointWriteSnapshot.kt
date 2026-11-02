package com.example.api

import com.example.points.CreatedPoint
import com.example.points.CreatedTopic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

/** Builds durable HTTP bodies from the same transaction that created the resources. */
object PointWriteSnapshot {
    fun create(connection: Connection, created: CreatedPoint): JsonObject {
        require(!connection.autoCommit)
        val point = readPoint(connection, created.pointId)
        val topic = readTopic(connection, created.topicId)
        val chat = readChat(connection, created.chatId, created.memberUserId, point, topic)
        return buildJsonObject {
            put("point", pointBody(connection, point))
            put("chat", chat)
            put("quota", quotaBody(connection, created.memberUserId))
        }
    }

    fun addTopic(connection: Connection, created: CreatedTopic): JsonObject {
        require(!connection.autoCommit)
        val point = readPoint(connection, created.pointId)
        val topic = readTopic(connection, created.topicId)
        return buildJsonObject {
            put("topic", topicBody(topic))
            put("chat", readChat(connection, created.chatId, created.memberUserId, point, topic))
            put("membership", membershipBody(connection, created.chatId, created.memberUserId))
        }
    }

    private data class PointRow(
        val id: UUID, val lat: Double, val lon: Double, val address: String,
        val source: String, val createdAt: String, val updatedAt: String,
    )

    private data class TopicRow(
        val id: UUID, val presetKey: String, val label: String, val count: Int, val chatId: UUID,
    )

    private fun readPoint(connection: Connection, id: UUID): PointRow = connection.prepareStatement(
        """SELECT id,ST_Y(location),ST_X(location),address,address_source,created_at,updated_at
           FROM points WHERE id=?"""
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Created point disappeared before snapshot" }
            PointRow(
                rows.getObject(1, UUID::class.java), rows.getDouble(2), rows.getDouble(3),
                rows.getString(4), rows.getString(5),
                rows.getObject(6, OffsetDateTime::class.java).toInstant().toString(),
                rows.getObject(7, OffsetDateTime::class.java).toInstant().toString(),
            )
        }
    }

    private fun readTopic(connection: Connection, id: UUID): TopicRow = connection.prepareStatement(
        """SELECT t.id,t.preset_key,coalesce(t.custom_label,p.label_ru),c.participant_count,c.id
           FROM point_topics AS t JOIN topic_presets AS p ON p.key=t.preset_key
           JOIN chats AS c ON c.point_topic_id=t.id WHERE t.id=?"""
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Created topic disappeared before snapshot" }
            TopicRow(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3),
                rows.getInt(4), rows.getObject(5, UUID::class.java))
        }
    }

    private fun pointBody(connection: Connection, point: PointRow): JsonObject {
        val topics = connection.prepareStatement(
            """SELECT t.id,t.preset_key,coalesce(t.custom_label,p.label_ru),c.participant_count,c.id
               FROM point_topics AS t JOIN topic_presets AS p ON p.key=t.preset_key
               JOIN chats AS c ON c.point_topic_id=t.id
               WHERE t.point_id=? ORDER BY p.sort_order,t.id"""
        ).use { statement ->
            statement.setObject(1, point.id)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(TopicRow(rows.getObject(1, UUID::class.java), rows.getString(2),
                    rows.getString(3), rows.getInt(4), rows.getObject(5, UUID::class.java)))
            } }
        }
        val people = connection.prepareStatement(
            """SELECT count(DISTINCT m.user_id) FROM point_topics AS t
               JOIN chats AS c ON c.point_topic_id=t.id
               JOIN chat_memberships AS m ON m.chat_id=c.id AND m.left_at IS NULL
               WHERE t.point_id=?"""
        ).use { statement ->
            statement.setObject(1, point.id)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
        return buildJsonObject {
            put("id", point.id.toString())
            put("position", buildJsonObject { put("lat", point.lat); put("lon", point.lon) })
            put("address", point.address)
            put("addressSource", source(point.source))
            put("peopleCount", people)
            put("topics", JsonArray(topics.map(::topicBody)))
            put("createdAt", point.createdAt)
            put("updatedAt", point.updatedAt)
        }
    }

    private fun topicBody(topic: TopicRow): JsonObject = buildJsonObject {
        put("id", topic.id.toString())
        put("presetKey", topic.presetKey)
        put("label", topic.label)
        put("participantCount", topic.count)
        put("chatId", topic.chatId.toString())
    }

    private fun readChat(
        connection: Connection, chatId: UUID, userId: UUID, point: PointRow, topic: TopicRow,
    ): JsonObject {
        val last = connection.prepareStatement(
            """SELECT id,type,system_type,created_at FROM messages
               WHERE chat_id=? ORDER BY message_seq DESC LIMIT 1"""
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "New chat has no join message" }
                check(rows.getString(2) == "SYSTEM" && rows.getString(3) == "member_joined") {
                    "New chat has an unexpected last message"
                }
                buildJsonObject {
                    put("id", rows.getObject(1, UUID::class.java).toString())
                    put("type", "system")
                    put("text", "Участник присоединился")
                    put("createdAt", rows.getObject(4, OffsetDateTime::class.java).toInstant().toString())
                }
            }
        }
        val unread = connection.prepareStatement(
            """SELECT count(*) FROM messages AS message JOIN chat_memberships AS membership
               ON membership.chat_id=message.chat_id AND membership.user_id=?
               WHERE message.chat_id=? AND message.message_seq>membership.last_read_seq"""
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
        return buildJsonObject {
            put("id", chatId.toString())
            put("pointId", point.id.toString())
            put("topic", topicBody(topic))
            put("address", point.address)
            put("participantCount", topic.count)
            put("lastMessage", last)
            put("unreadCount", unread)
            put("currentProposal", JsonNull)
            put("updatedAt", point.updatedAt)
        }
    }

    private fun membershipBody(connection: Connection, chatId: UUID, userId: UUID): JsonObject =
        connection.prepareStatement(
            """SELECT joined_at,last_read_seq FROM chat_memberships
               WHERE chat_id=? AND user_id=? AND left_at IS NULL"""
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Created membership disappeared before snapshot" }
                buildJsonObject {
                    put("chatId", chatId.toString())
                    put("userId", userId.toString())
                    put("joinedAt", rows.getObject(1, OffsetDateTime::class.java).toInstant().toString())
                    put("lastReadMessageSeq", rows.getLong(2))
                }
            }
        }

    private fun quotaBody(connection: Connection, userId: UUID): JsonObject = connection.prepareStatement(
        """WITH clock_now AS (SELECT clock_timestamp() AS ts)
           SELECT count(*) FILTER (WHERE event.created_at > now.ts - interval '168 hours'),
                  count(*) FILTER (WHERE event.created_at > now.ts - interval '720 hours'),
                  min(event.created_at) FILTER (WHERE event.created_at > now.ts - interval '168 hours'),
                  min(event.created_at) FILTER (WHERE event.created_at > now.ts - interval '720 hours'),
                  user_row.create_point_blocked_until,now.ts
           FROM users AS user_row CROSS JOIN clock_now AS now
           LEFT JOIN point_creation_events AS event ON event.creator_user_id=user_row.id
           WHERE user_row.id=? AND user_row.deleted_at IS NULL
           GROUP BY user_row.id,user_row.create_point_blocked_until,now.ts"""
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Active creator disappeared before snapshot" }
            val weekUsed = rows.getInt(1)
            val monthUsed = rows.getInt(2)
            val weekEarliest = rows.getObject(3, OffsetDateTime::class.java)?.toInstant()
            val monthEarliest = rows.getObject(4, OffsetDateTime::class.java)?.toInstant()
            val now = rows.getObject(6, OffsetDateTime::class.java).toInstant()
            val blocked = rows.getObject(5, OffsetDateTime::class.java)?.toInstant()?.takeIf { it > now }
            val weekRemaining = (3 - weekUsed).coerceAtLeast(0)
            val monthRemaining = (10 - monthUsed).coerceAtLeast(0)
            buildJsonObject {
                put("weekUsed", weekUsed)
                put("weekLimit", 3)
                put("weekRemaining", weekRemaining)
                put("weekNextAvailableAt", if (weekUsed >= 3) weekEarliest?.plusSeconds(168 * 3600L)?.toString() else null)
                put("monthUsed", monthUsed)
                put("monthLimit", 10)
                put("monthRemaining", monthRemaining)
                put("monthNextAvailableAt", if (monthUsed >= 10) monthEarliest?.plusSeconds(720 * 3600L)?.toString() else null)
                put("blockedUntil", blocked?.toString())
                put("lastAvailableWarning", blocked == null && (weekRemaining == 1 || monthRemaining == 1))
            }
        }
    }

    private fun source(value: String): String = when (value) {
        "GEOCODED" -> "provider"
        "MANUAL" -> "manual"
        else -> error("Unknown point address source")
    }
}
