package com.example.points

import com.example.persistence.DatabaseRuntime
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TopicPresetRead(val key: String, val label: String, val iconKey: String)
data class PointTopicRead(
    val id: UUID,
    val presetKey: String,
    val label: String,
    val participantCount: Int,
    val chatId: UUID,
)
data class PointRead(
    val id: UUID,
    val lat: Double,
    val lon: Double,
    val address: String,
    val addressSource: String,
    val peopleCount: Int,
    val topics: List<PointTopicRead>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
data class PointPageRead(val items: List<PointRead>, val nextCursor: String?)
data class PointPreviewRead(val id: UUID, val lat: Double, val lon: Double, val address: String, val distanceMeters: Int)
data class PointQuotaRead(
    val weekUsed: Int,
    val monthUsed: Int,
    val weekNextAvailableAt: Instant?,
    val monthNextAvailableAt: Instant?,
    val blockedUntil: Instant?,
) {
    val weekLimit: Int get() = 3
    val monthLimit: Int get() = 10
    val weekRemaining: Int get() = (weekLimit - weekUsed).coerceAtLeast(0)
    val monthRemaining: Int get() = (monthLimit - monthUsed).coerceAtLeast(0)
    val lastAvailableWarning: Boolean get() = blockedUntil == null && (weekRemaining == 1 || monthRemaining == 1)
}
data class PointCreationContextRead(val nearby: List<PointPreviewRead>, val quota: PointQuotaRead)

data class PointMapFilter(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val topicKey: String? = null,
    val customTopicLabel: String? = null,
    val peopleMin: Int = 0,
    val peopleMax: Int = 50,
    val limit: Int = 30,
) {
    init {
        require(listOf(west, south, east, north).all(Double::isFinite) &&
            west in -180.0..180.0 && east in -180.0..180.0 &&
            south in -90.0..90.0 && north in -90.0..90.0 && west < east && south < north) { "Invalid bbox" }
        require(peopleMin in 0..50 && peopleMax in peopleMin..50) { "Invalid people range" }
        require(limit in 1..100) { "Invalid page limit" }
        require(topicKey == null || topicKey in TopicPresetKeys.allowed) { "Invalid topic key" }
        require(customTopicLabel == null || (topicKey == "other" && customTopicLabel.isNotBlank() &&
            customTopicLabel.trim().codePointCount(0, customTopicLabel.trim().length) <= 40)) {
            "Custom topic label requires other"
        }
    }

    internal fun fingerprint(): String {
        val values = listOf(
            west, south, east, north, topicKey ?: "", customTopicLabel?.trim()?.lowercase() ?: "",
            peopleMin, peopleMax, limit,
        ).map { it.toString() }
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** All map hydration queries use one short REPEATABLE READ snapshot. */
class PointReadRepository(private val database: DatabaseRuntime, cursorSecret: String) {
    private val cursorCodec = PointCursorCodec(cursorSecret)

    suspend fun presets(): List<TopicPresetRead> = database.withReadOnlyJdbcTransaction { connection ->
        connection.prepareStatement("SELECT key,label_ru,icon_key FROM topic_presets ORDER BY sort_order").use { statement ->
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(TopicPresetRead(rows.getString(1), rows.getString(2), rows.getString(3)))
            } }
        }
    }

    suspend fun page(filter: PointMapFilter, cursor: String? = null): PointPageRead =
        database.withReadOnlyJdbcTransaction { connection ->
            val after = cursor?.let { cursorCodec.decode(it, filter) }
            val heads = connection.prepareStatement(
                """SELECT p.id, ST_Y(p.location), ST_X(p.location), p.address, p.address_source,
                          p.created_at, p.updated_at
                   FROM points AS p
                   WHERE p.location && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                     AND (?::timestamptz IS NULL OR (p.created_at,p.id) < (?,?))
                     AND EXISTS (
                         SELECT 1 FROM point_topics AS pt
                         JOIN chats AS c ON c.point_topic_id = pt.id
                         WHERE pt.point_id = p.id
                           AND (?::text IS NULL OR pt.preset_key = ?)
                           AND (?::text IS NULL OR lower(btrim(pt.custom_label)) = ?)
                           AND c.participant_count BETWEEN ? AND ?
                     )
                   ORDER BY p.created_at DESC,p.id DESC
                   LIMIT ?"""
            ).use { statement ->
                statement.setDouble(1, filter.west)
                statement.setDouble(2, filter.south)
                statement.setDouble(3, filter.east)
                statement.setDouble(4, filter.north)
                val afterTime = after?.first?.atOffset(ZoneOffset.UTC)
                statement.setObject(5, afterTime)
                statement.setObject(6, afterTime)
                statement.setObject(7, after?.second)
                statement.setString(8, filter.topicKey)
                statement.setString(9, filter.topicKey)
                val custom = filter.customTopicLabel?.trim()?.lowercase()
                statement.setString(10, custom)
                statement.setString(11, custom)
                statement.setInt(12, filter.peopleMin)
                statement.setInt(13, filter.peopleMax)
                statement.setInt(14, filter.limit + 1)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(PointHead(
                        rows.getObject(1, UUID::class.java), rows.getDouble(2), rows.getDouble(3),
                        rows.getString(4), rows.getString(5), rows.getObject(6, OffsetDateTime::class.java).toInstant(),
                        rows.getObject(7, OffsetDateTime::class.java).toInstant(),
                    ))
                } }
            }
            val visible = heads.take(filter.limit)
            val ids = visible.map { it.id }
            val topics = readTopics(connection, ids)
            val people = readPeopleCounts(connection, ids)
            PointPageRead(
                visible.map { head -> head.toPoint(topics[head.id].orEmpty(), people[head.id] ?: 0) },
                if (heads.size > filter.limit) visible.lastOrNull()?.let { cursorCodec.encode(filter, it.createdAt, it.id) } else null,
            )
        }

    suspend fun detail(id: UUID): PointRead? = database.withReadOnlyJdbcTransaction { connection ->
        val head = connection.prepareStatement(
            """SELECT id,ST_Y(location),ST_X(location),address,address_source,created_at,updated_at
               FROM points WHERE id = ?"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) PointHead(
                    rows.getObject(1, UUID::class.java), rows.getDouble(2), rows.getDouble(3),
                    rows.getString(4), rows.getString(5), rows.getObject(6, OffsetDateTime::class.java).toInstant(),
                    rows.getObject(7, OffsetDateTime::class.java).toInstant(),
                ) else null
            }
        } ?: return@withReadOnlyJdbcTransaction null
        head.toPoint(readTopics(connection, listOf(id))[id].orEmpty(), readPeopleCounts(connection, listOf(id))[id] ?: 0)
    }

    suspend fun quota(userId: UUID): PointQuotaRead = database.withReadOnlyJdbcTransaction { connection ->
        readQuota(connection, userId)
    }

    suspend fun creationContext(userId: UUID, lat: Double, lon: Double): PointCreationContextRead {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        return database.withReadOnlyJdbcTransaction { connection ->
            val nearby = connection.prepareStatement(
                """SELECT id,ST_Y(location),ST_X(location),address,
                          round(ST_Distance(location::geography,ST_SetSRID(ST_MakePoint(?,?),4326)::geography))::integer
                   FROM points
                   WHERE ST_DWithin(location::geography,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,100)
                   ORDER BY location::geography <-> ST_SetSRID(ST_MakePoint(?,?),4326)::geography,id"""
            ).use { statement ->
                statement.setDouble(1, lon); statement.setDouble(2, lat)
                statement.setDouble(3, lon); statement.setDouble(4, lat)
                statement.setDouble(5, lon); statement.setDouble(6, lat)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(PointPreviewRead(
                        rows.getObject(1, UUID::class.java), rows.getDouble(2), rows.getDouble(3),
                        rows.getString(4), rows.getInt(5),
                    ))
                } }
            }
            PointCreationContextRead(nearby, readQuota(connection, userId))
        }
    }

    private fun readQuota(connection: Connection, userId: UUID): PointQuotaRead =
        PointQuotaSql.read(connection, userId, PointQuotaSql.databaseNow(connection))

    private fun readTopics(connection: Connection, ids: List<UUID>): Map<UUID, List<PointTopicRead>> {
        if (ids.isEmpty()) return emptyMap()
        val array = connection.createArrayOf("uuid", ids.toTypedArray())
        return try {
            connection.prepareStatement(
                """SELECT pt.point_id,pt.id,pt.preset_key,coalesce(pt.custom_label,p.label_ru),c.participant_count,c.id
                   FROM point_topics AS pt
                   JOIN topic_presets AS p ON p.key = pt.preset_key
                   JOIN chats AS c ON c.point_topic_id = pt.id
                   WHERE pt.point_id = ANY (?)
                   ORDER BY pt.point_id,p.sort_order,lower(coalesce(pt.custom_label,p.label_ru)),pt.id"""
            ).use { statement ->
                statement.setArray(1, array)
                statement.executeQuery().use { rows -> buildMap<UUID, MutableList<PointTopicRead>> {
                    while (rows.next()) getOrPut(rows.getObject(1, UUID::class.java)) { mutableListOf() }.add(
                        PointTopicRead(rows.getObject(2, UUID::class.java), rows.getString(3), rows.getString(4),
                            rows.getInt(5), rows.getObject(6, UUID::class.java))
                    )
                } }
            }
        } finally { array.free() }
    }

    private fun readPeopleCounts(connection: Connection, ids: List<UUID>): Map<UUID, Int> {
        if (ids.isEmpty()) return emptyMap()
        val array = connection.createArrayOf("uuid", ids.toTypedArray())
        return try {
            connection.prepareStatement(
                """SELECT pt.point_id,count(DISTINCT m.user_id)
                   FROM point_topics AS pt
                   JOIN chats AS c ON c.point_topic_id = pt.id
                   JOIN chat_memberships AS m ON m.chat_id = c.id AND m.left_at IS NULL
                   WHERE pt.point_id = ANY (?) GROUP BY pt.point_id"""
            ).use { statement ->
                statement.setArray(1, array)
                statement.executeQuery().use { rows -> buildMap {
                    while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getInt(2))
                } }
            }
        } finally { array.free() }
    }

    private data class PointHead(
        val id: UUID, val lat: Double, val lon: Double, val address: String, val addressSource: String,
        val createdAt: Instant, val updatedAt: Instant,
    ) {
        fun toPoint(topics: List<PointTopicRead>, peopleCount: Int) = PointRead(
            id, lat, lon, address, addressSource, peopleCount, topics, createdAt, updatedAt,
        )
    }
}

/** Opaque, versioned cursor bound to all map filters and signed against tampering. */
private class PointCursorCodec(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    init { require(secret.toByteArray(StandardCharsets.UTF_8).size >= 32) { "Cursor secret must have at least 32 UTF-8 bytes" } }

    fun encode(filter: PointMapFilter, createdAt: Instant, id: UUID): String {
        val payload = "1|points|${filter.fingerprint()}|$createdAt|$id"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        return encoder.encodeToString(bytes) + "." + encoder.encodeToString(sign(bytes))
    }

    fun decode(cursor: String, filter: PointMapFilter): Pair<Instant, UUID> {
        require(cursor.length <= 1024)
        val parts = cursor.split('.')
        require(parts.size == 2)
        val bytes = try { decoder.decode(parts[0]) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid cursor") }
        val signature = try { decoder.decode(parts[1]) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid cursor") }
        require(MessageDigest.isEqual(sign(bytes), signature)) { "Invalid cursor signature" }
        val text = bytes.toString(StandardCharsets.UTF_8)
        val prefix = "1|points|${filter.fingerprint()}|"
        require(text.startsWith(prefix)) { "Cursor filter mismatch" }
        val tail = text.removePrefix(prefix).split('|')
        require(tail.size == 2)
        return try { Instant.parse(tail[0]) to UUID.fromString(tail[1]) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid cursor") }
    }

    private fun sign(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(bytes)
}
