package com.example.notifications

import com.example.chats.ChatCursorCodec
import com.example.persistence.DatabaseRuntime
import com.example.persistence.withRetryingJdbcTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class NotificationSettingsView(
    val remindersPushEnabled: Boolean, val personalPushEnabled: Boolean,
    val extraPushEnabled: Boolean, val extraInAppEnabled: Boolean, val revision: Long,
)

data class NotificationSettingsChange(
    val remindersPushEnabled: Boolean, val personalPushEnabled: Boolean,
    val extraPushEnabled: Boolean, val extraInAppEnabled: Boolean, val expectedRevision: Long,
)

sealed interface SettingsUpdateResult {
    data class Updated(val settings: NotificationSettingsView) : SettingsUpdateResult
    data object Stale : SettingsUpdateResult
    data object Unavailable : SettingsUpdateResult
}

data class NotificationView(
    val id: UUID, val kind: String, val title: String, val body: String,
    val pointId: UUID?, val chatId: UUID?, val proposalId: UUID?,
    val createdAt: Instant, val readAt: Instant?,
)

data class NotificationPage(val items: List<NotificationView>, val nextCursor: String?)
data class NotificationLocationView(val latitude: Double?, val longitude: Double?, val updatedAt: Instant?)

sealed interface LocationWriteResult {
    data class Updated(val location: NotificationLocationView) : LocationWriteResult
    data object DisabledBySettings : LocationWriteResult
    data object PolicyUnavailable : LocationWriteResult
    data object PolicyNotAccepted : LocationWriteResult
    data object InvalidPosition : LocationWriteResult
    data object AccountUnavailable : LocationWriteResult
}

/** Account settings, visible notification history and voluntary map center. */
class NotificationService(
    private val database: DatabaseRuntime,
    cursorSecret: String,
    private val locationPrivacyVersion: String? = null,
) {
    private val cursors = ChatCursorCodec(cursorSecret)

    suspend fun settings(userId: UUID): NotificationSettingsView? = database.withReadOnlyJdbcTransaction { connection ->
        readSettings(connection, userId)
    }

    suspend fun updateSettings(userId: UUID, change: NotificationSettingsChange): SettingsUpdateResult {
        if (change.expectedRevision < 0) return SettingsUpdateResult.Stale
        return database.withRetryingJdbcTransaction { connection ->
            if (!lockActiveUser(connection, userId)) return@withRetryingJdbcTransaction SettingsUpdateResult.Unavailable
            val current = connection.prepareStatement(
                """SELECT revision FROM notification_settings WHERE user_id=? FOR UPDATE"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            } ?: return@withRetryingJdbcTransaction SettingsUpdateResult.Unavailable
            if (current != change.expectedRevision) return@withRetryingJdbcTransaction SettingsUpdateResult.Stale
            connection.prepareStatement(
                """UPDATE notification_settings SET reminders_push_enabled=?,personal_push_enabled=?,
                   extra_push_enabled=?,extra_in_app_enabled=?,revision=revision+1,updated_at=clock_timestamp()
                   WHERE user_id=?"""
            ).use { statement ->
                statement.setBoolean(1, change.remindersPushEnabled)
                statement.setBoolean(2, change.personalPushEnabled)
                statement.setBoolean(3, change.extraPushEnabled)
                statement.setBoolean(4, change.extraInAppEnabled)
                statement.setObject(5, userId)
                check(statement.executeUpdate() == 1)
            }
            if (!change.extraPushEnabled && !change.extraInAppEnabled) {
                connection.prepareStatement("DELETE FROM notification_locations WHERE user_id=?").use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            }
            val disabledChannels = buildList {
                if (!change.remindersPushEnabled) add("REMINDERS")
                if (!change.personalPushEnabled) add("PERSONAL")
                if (!change.extraPushEnabled) add("EXTRA")
            }
            if (disabledChannels.isNotEmpty()) cancelDeliveryForChannels(connection, userId, disabledChannels)
            SettingsUpdateResult.Updated(checkNotNull(readSettings(connection, userId)))
        }
    }

    suspend fun list(userId: UUID, cursor: String?, limit: Int): NotificationPage {
        require(limit in 1..100)
        val fingerprint = fingerprint("notifications:$userId:$limit")
        val after = cursor?.let { cursors.decode("notifications", fingerprint, it) }
        require(after?.third == null) { "Invalid cursor" }
        return database.withReadOnlyJdbcTransaction { connection ->
            val rows = connection.prepareStatement(
                """SELECT id,event_type,display_snapshot,point_id,chat_id,proposal_id,created_at,read_at
                   FROM notification_events WHERE recipient_user_id=? AND visible_in_app=true
                     AND (?::timestamptz IS NULL OR (created_at,id)<(?,?))
                   ORDER BY created_at DESC,id DESC LIMIT ?"""
            ).use { statement ->
                statement.setObject(1, userId)
                val at = after?.first?.atOffset(ZoneOffset.UTC)
                statement.setObject(2, at)
                statement.setObject(3, at)
                statement.setObject(4, after?.second)
                statement.setInt(5, limit + 1)
                statement.executeQuery().use { results -> buildList {
                    while (results.next()) add(readNotification(results))
                } }
            }
            val page = rows.take(limit)
            NotificationPage(page, if (rows.size > limit) page.lastOrNull()?.let {
                cursors.encode("notifications", fingerprint, it.createdAt, it.id)
            } else null)
        }
    }

    suspend fun markRead(userId: UUID, notificationId: UUID): NotificationView? =
        database.withRetryingJdbcTransaction { connection ->
            if (!lockActiveUser(connection, userId)) return@withRetryingJdbcTransaction null
            connection.prepareStatement(
                """UPDATE notification_events SET read_at=COALESCE(read_at,clock_timestamp())
                   WHERE id=? AND recipient_user_id=? AND visible_in_app=true
                   RETURNING id,event_type,display_snapshot,point_id,chat_id,proposal_id,created_at,read_at"""
            ).use { statement ->
                statement.setObject(1, notificationId)
                statement.setObject(2, userId)
                statement.executeQuery().use { rows -> if (rows.next()) readNotification(rows) else null }
            }
        }

    suspend fun location(userId: UUID): NotificationLocationView = database.withReadOnlyJdbcTransaction { connection ->
        readLocation(connection, userId)
    }

    suspend fun setLocation(userId: UUID, latitude: Double, longitude: Double): LocationWriteResult {
        if (locationPrivacyVersion == null) return LocationWriteResult.PolicyUnavailable
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return LocationWriteResult.InvalidPosition
        return database.withRetryingJdbcTransaction { connection ->
            if (!lockActiveUser(connection, userId)) return@withRetryingJdbcTransaction LocationWriteResult.AccountUnavailable
            val acceptedPolicy = connection.prepareStatement(
                "SELECT accepted_privacy_version FROM users WHERE id=?"
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (acceptedPolicy != locationPrivacyVersion) {
                return@withRetryingJdbcTransaction LocationWriteResult.PolicyNotAccepted
            }
            val enabled = connection.prepareStatement(
                "SELECT extra_push_enabled OR extra_in_app_enabled FROM notification_settings WHERE user_id=? FOR UPDATE"
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
            if (!enabled) return@withRetryingJdbcTransaction LocationWriteResult.DisabledBySettings
            connection.prepareStatement(
                """INSERT INTO notification_locations (user_id,position,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,clock_timestamp())
                   ON CONFLICT (user_id) DO UPDATE SET position=EXCLUDED.position,updated_at=EXCLUDED.updated_at"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setDouble(2, longitude)
                statement.setDouble(3, latitude)
                check(statement.executeUpdate() == 1)
            }
            LocationWriteResult.Updated(readLocation(connection, userId))
        }
    }

    suspend fun deleteLocation(userId: UUID) {
        database.withRetryingJdbcTransaction { connection ->
            if (lockActiveUser(connection, userId)) connection.prepareStatement(
                "DELETE FROM notification_locations WHERE user_id=?"
            ).use { statement -> statement.setObject(1, userId); statement.executeUpdate() }
        }
    }

    private fun readSettings(connection: Connection, userId: UUID): NotificationSettingsView? = connection.prepareStatement(
        """SELECT reminders_push_enabled,personal_push_enabled,extra_push_enabled,extra_in_app_enabled,revision
           FROM notification_settings WHERE user_id=?"""
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rows -> if (rows.next()) NotificationSettingsView(
            rows.getBoolean(1), rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4), rows.getLong(5)
        ) else null }
    }

    private fun readLocation(connection: Connection, userId: UUID): NotificationLocationView = connection.prepareStatement(
        "SELECT ST_Y(position::geometry),ST_X(position::geometry),updated_at FROM notification_locations WHERE user_id=?"
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rows -> if (rows.next()) NotificationLocationView(
            rows.getDouble(1), rows.getDouble(2), rows.getObject(3, OffsetDateTime::class.java).toInstant()
        ) else NotificationLocationView(null, null, null) }
    }

    private fun lockActiveUser(connection: Connection, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM users WHERE id=? AND deleted_at IS NULL FOR NO KEY UPDATE"
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { it.next() }
    }

    private fun cancelDeliveryForChannels(connection: Connection, userId: UUID, channels: List<String>) {
        val channelArray = connection.createArrayOf("text", channels.toTypedArray())
        try {
            connection.prepareStatement(
                """UPDATE push_delivery_jobs AS job SET status='CANCELLED',claim_token=NULL,lease_until=NULL,
                   completed_at=clock_timestamp()
                   FROM notification_events AS event WHERE job.notification_event_id=event.id
                     AND event.recipient_user_id=? AND event.channel=ANY (?)
                     AND job.status IN ('PENDING','PROCESSING')"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setArray(2, channelArray)
                statement.executeUpdate()
            }
        } finally { channelArray.free() }
    }

    private fun readNotification(rows: java.sql.ResultSet): NotificationView {
        val snapshot = Json.parseToJsonElement(rows.getString("display_snapshot")) as JsonObject
        val kind = rows.getString("event_type")
        val nearby = kind == "nearby_meeting"
        return NotificationView(
            rows.getObject("id", UUID::class.java), kind,
            snapshot["title"]?.jsonPrimitive?.content ?: "Уведомление",
            snapshot["body"]?.jsonPrimitive?.content ?: "",
            rows.getObject("point_id", UUID::class.java),
            if (nearby) null else rows.getObject("chat_id", UUID::class.java),
            if (nearby) null else rows.getObject("proposal_id", UUID::class.java),
            rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            rows.getObject("read_at", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    private fun fingerprint(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
