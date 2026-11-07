package com.example.auth

import com.example.api.ApiException
import com.example.persistence.DatabaseRuntime
import com.example.persistence.withRetryingJdbcTransaction
import com.example.storage.AvatarImage
import com.example.storage.AvatarStorageException
import com.example.storage.AvatarStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class UpdateProfileRequest(val displayName: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String) {
    override fun toString(): String = "ChangePasswordRequest(redacted)"
}

@Serializable
data class AvatarUpdateResponse(val avatarUrl: String)

private const val STAGED_AVATAR_GRACE_SECONDS = 15 * 60
private val avatarLog = LoggerFactory.getLogger(ProfileService::class.java)

internal fun avatarNotFound(): Nothing = throw ApiException(
    HttpStatusCode.NotFound, "RESOURCE_GONE", "Ресурс не найден",
)

/** Profile mutations use the same users-first lock order as login and refresh. */
class ProfileService(
    private val database: DatabaseRuntime,
    private val passwordHashing: PasswordHashingWork,
    private val avatarStore: AvatarStore? = null,
) {
    internal suspend fun readAvatar(identity: SessionIdentity, targetId: UUID): AvatarImage {
        val key = accessibleAvatarKey(identity, targetId)
        val store = avatarStore ?: avatarStorageUnavailable()
        val read = try {
            Result.success(store.read(key))
        } catch (failure: AvatarStorageException) {
            Result.failure(failure)
        }
        // The provider call holds no database connection. Recheck even on failure so
        // a request that lost access while waiting cannot distinguish storage outcomes.
        if (accessibleAvatarKey(identity, targetId) != key) avatarNotFound()
        return storageOperation { read.getOrThrow() } ?: avatarNotFound()
    }

    private suspend fun accessibleAvatarKey(identity: SessionIdentity, targetId: UUID): String =
        database.withReadOnlyJdbcTransaction { connection ->
            // One snapshot distinguishes a dead viewer session from an inaccessible target.
            connection.prepareStatement(
                """SELECT target.avatar_object_key
                   FROM auth_sessions AS session
                   JOIN users AS viewer ON viewer.id=session.user_id
                   LEFT JOIN users AS target ON target.id=? AND target.deleted_at IS NULL
                       AND (target.id=viewer.id OR EXISTS (
                           SELECT 1 FROM chat_memberships AS viewer_membership
                           JOIN chat_memberships AS target_membership
                             ON target_membership.chat_id=viewer_membership.chat_id
                            AND target_membership.user_id=target.id AND target_membership.left_at IS NULL
                           WHERE viewer_membership.user_id=viewer.id AND viewer_membership.left_at IS NULL
                       ))
                   WHERE session.id=? AND viewer.id=? AND viewer.auth_version=?
                     AND (session.auth_version IS NULL OR session.auth_version=viewer.auth_version)
                     AND viewer.deleted_at IS NULL
                     AND session.revoked_at IS NULL AND session.expires_at>clock_timestamp()"""
            ).use { statement ->
                statement.setObject(1, targetId)
                statement.setObject(2, identity.sessionId)
                statement.setObject(3, identity.userId)
                statement.setInt(4, identity.authVersion)
                statement.executeQuery().use { rows ->
                    if (!rows.next() || !identity.accessExpiresAt.isAfter(Instant.now())) sessionExpired()
                    rows.getString(1) ?: avatarNotFound()
                }
            }
        }

    /** Stages a processed object before any profile transaction. The key stays server-side. */
    internal suspend fun stageAvatar(image: AvatarImage): String {
        val store = avatarStore ?: avatarStorageUnavailable()
        return storageOperation { store.save(image) }
    }

    private inline fun <T> storageOperation(block: () -> T): T = try {
        block()
    } catch (_: AvatarStorageException) {
        avatarStorageUnavailable()
    }

    /** Capture before reading the body so a slow upload cannot adopt a newer profile state. */
    internal suspend fun captureAvatarState(identity: SessionIdentity): AvatarState =
        database.withRetryingJdbcTransaction { lockedAvatarState(it, identity) }

    /** The durable intent exists before a provider can create the object. */
    internal suspend fun replaceAvatar(
        identity: SessionIdentity,
        current: AvatarState,
        image: AvatarImage,
    ): AvatarUpdateResponse {
        val store = avatarStore ?: avatarStorageUnavailable()
        val response = AvatarUpdateResponse(avatarUrl(identity.userId))
        val existing = current.objectKey?.let { key -> storageOperation { store.read(key) } }
        if (existing != null && existing.contentType == image.contentType && existing.bytes.contentEquals(image.bytes)) {
            database.withRetryingJdbcTransaction { requireCurrentAvatar(it, identity, current) }
            return response
        }

        val stagedId = UUID.randomUUID()
        val newKey = stagedId.toString()
        database.withRetryingJdbcTransaction { connection ->
            requireCurrentAvatar(connection, identity, current)
            enqueueAvatarCleanup(connection, stagedId, newKey, STAGED_AVATAR_GRACE_SECONDS)
            // A staged object uses its UUID as the job PK. The post-insert check also
            // covers an earlier owner consuming that UUID's intent concurrently.
            check(!avatarReferenced(connection, newKey))
        }

        var writeAcknowledged = false
        try {
            storageOperation { store.save(image, stagedId) }
            writeAcknowledged = true
            database.withRetryingJdbcTransaction { connection ->
                requireCurrentAvatar(connection, identity, current)
                val consumed = connection.prepareStatement(
                    """DELETE FROM media_deletion_jobs WHERE id=? AND object_key=? AND status='PENDING'
                       AND attempts=0 AND claim_token IS NULL AND lease_until IS NULL
                       AND created_at+(? * interval '1 second')>clock_timestamp()
                       AND next_attempt_at>clock_timestamp()"""
                ).use {
                    it.setObject(1, stagedId)
                    it.setString(2, newKey)
                    it.setInt(3, STAGED_AVATAR_GRACE_SECONDS)
                    it.executeUpdate() == 1
                }
                // A retry can move next_attempt_at or reset attempts after a worker touched the object.
                if (!consumed) avatarStorageUnavailable()
                writeAvatarKey(connection, identity.userId, newKey)
                current.objectKey?.let { enqueueAvatarCleanup(connection, UUID.randomUUID(), it) }
            }
        } catch (failure: Exception) {
            compensateAvatar(store, stagedId, newKey, writeAcknowledged)
            throw failure
        }
        return response
    }

    suspend fun removeAvatar(identity: SessionIdentity) {
        database.withRetryingJdbcTransaction { connection ->
            val current = lockedAvatarState(connection, identity)
            val oldKey = current.objectKey ?: return@withRetryingJdbcTransaction
            writeAvatarKey(connection, identity.userId, null)
            enqueueAvatarCleanup(connection, UUID.randomUUID(), oldKey)
        }
    }

    private suspend fun compensateAvatar(store: AvatarStore, id: UUID, key: String, writeAcknowledged: Boolean) {
        withContext(NonCancellable) {
            try {
                // The update waits for an in-flight swap to settle. A committed swap
                // consumed the row, even if the request never received commit confirmation.
                val unbound = database.withRetryingJdbcTransaction { connection ->
                    val pending = connection.prepareStatement(
                        """UPDATE media_deletion_jobs
                           SET status='PENDING', attempts=0, claim_token=NULL, lease_until=NULL, completed_at=NULL,
                               next_attempt_at=clock_timestamp()+(? * interval '1 second')
                           WHERE id=? AND object_key=?"""
                    ).use {
                        it.setInt(1, STAGED_AVATAR_GRACE_SECONDS)
                        it.setObject(2, id)
                        it.setString(3, key)
                        it.executeUpdate() == 1
                    }
                    if (pending && avatarReferenced(connection, key)) {
                        // Do not leave an executable delete job for a referenced object.
                        deleteAvatarIntent(connection, id, key)
                        false
                    } else pending
                }
                if (!unbound) return@withContext
                store.delete(key)
                if (writeAcknowledged) {
                    database.withRetryingJdbcTransaction { deleteAvatarIntent(it, id, key) }
                }
                // Keep the delayed intent after a failed PUT even if delete reports success.
                // The provider might still finish the PUT after losing its response.
            } catch (_: Exception) {
                avatarLog.warn("Avatar compensation could not finish")
            }
        }
    }

    private fun deleteAvatarIntent(connection: Connection, id: UUID, key: String) {
        connection.prepareStatement(
            "DELETE FROM media_deletion_jobs WHERE id=? AND object_key=? AND status='PENDING'"
        ).use {
            it.setObject(1, id)
            it.setString(2, key)
            it.executeUpdate()
        }
    }

    private fun avatarReferenced(connection: Connection, key: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM users WHERE avatar_object_key=? LIMIT 1").use {
            it.setString(1, key)
            it.executeQuery().use { rows -> rows.next() }
        }

    private fun lockedAvatarState(connection: Connection, identity: SessionIdentity): AvatarState {
        val current = connection.prepareStatement(
            """SELECT avatar_object_key, updated_at FROM users
               WHERE id=? AND deleted_at IS NULL AND auth_version=? FOR NO KEY UPDATE"""
        ).use {
            it.setObject(1, identity.userId)
            it.setInt(2, identity.authVersion)
            it.executeQuery().use { rows ->
                if (!rows.next()) sessionExpired()
                AvatarState(rows.getString(1), rows.getObject(2, OffsetDateTime::class.java))
            }
        }
        // V004 kept existing access JWTs valid without assigning a session auth version.
        if (!sessionActive(connection, identity, allowLegacy = true) ||
            !identity.accessExpiresAt.isAfter(Instant.now())
        ) sessionExpired()
        return current
    }

    private fun requireCurrentAvatar(connection: Connection, identity: SessionIdentity, expected: AvatarState) {
        val current = lockedAvatarState(connection, identity)
        if (current != expected) throw ApiException(
            HttpStatusCode.Conflict, "STALE_STATE", "Профиль уже изменился",
            details = buildJsonObject {
                put("avatarUrl", current.objectKey?.let { JsonPrimitive(avatarUrl(identity.userId)) } ?: JsonNull)
            },
        )
    }

    private fun writeAvatarKey(connection: Connection, userId: UUID, key: String?) {
        connection.prepareStatement(
            "UPDATE users SET avatar_object_key=?,updated_at=clock_timestamp() WHERE id=?"
        ).use {
            it.setString(1, key)
            it.setObject(2, userId)
            check(it.executeUpdate() == 1)
        }
    }

    private fun enqueueAvatarCleanup(connection: Connection, id: UUID, key: String, delaySeconds: Int = 0) {
        connection.prepareStatement(
            """INSERT INTO media_deletion_jobs (id,object_key,status,attempts,next_attempt_at,created_at)
               VALUES (?,?,'PENDING',0,clock_timestamp()+(? * interval '1 second'),clock_timestamp())"""
        ).use {
            it.setObject(1, id)
            it.setString(2, key)
            it.setInt(3, delaySeconds)
            check(it.executeUpdate() == 1)
        }
    }

    private fun avatarUrl(userId: UUID): String = "/api/v1/users/$userId/avatar"

    internal data class AvatarState(val objectKey: String?, val updatedAt: OffsetDateTime)


    suspend fun updateName(identity: SessionIdentity, request: UpdateProfileRequest): UserSelf {
        val name = request.displayName.trim()
        if (name.codePointCount(0, name.length) !in 1..80 ||
            name.codePoints().anyMatch { codePoint -> codePoint == 0 || codePoint in 0xD800..0xDFFF }
        ) invalidField()
        return database.withRetryingJdbcTransaction { connection ->
            val current = lockUser(connection, identity) ?: sessionExpired()
            if (!sessionActive(connection, identity)) sessionExpired()
            if (current.displayName == name) return@withRetryingJdbcTransaction current
            connection.prepareStatement(
                "UPDATE users SET display_name=?,updated_at=clock_timestamp() WHERE id=?"
            ).use { statement ->
                statement.setString(1, name)
                statement.setObject(2, identity.userId)
                check(statement.executeUpdate() == 1)
            }
            current.copy(displayName = name)
        }
    }

    /** Hash and verify outside the row lock; CAS rechecks the hash/version and live session before commit. */
    suspend fun changePassword(identity: SessionIdentity, request: ChangePasswordRequest): List<UUID> {
        if (request.newPassword.codePointCount(0, request.newPassword.length) !in 8..128 ||
            request.currentPassword.isEmpty()
        ) invalidField()
        val snapshot = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                "SELECT password_hash,auth_version FROM users WHERE id=? AND deleted_at IS NULL"
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else rows.getString(1) to rows.getInt(2)
                }
            }
        } ?: sessionExpired()
        if (snapshot.second != identity.authVersion) sessionExpired()
        if (!passwordHashing.verify(request.currentPassword, snapshot.first)) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_CURRENT_PASSWORD", "Неверный текущий пароль")
        }
        val replacement = passwordHashing.hash(request.newPassword)
        return database.withRetryingJdbcTransaction { connection ->
            lockUser(connection, identity) ?: sessionExpired()
            if (!sessionActive(connection, identity)) sessionExpired()
            val stillCurrent = connection.prepareStatement(
                "SELECT password_hash,auth_version FROM users WHERE id=?"
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { rows -> rows.next() &&
                    rows.getString(1) == snapshot.first && rows.getInt(2) == snapshot.second }
            }
            if (!stillCurrent) sessionExpired()
            val sessions = connection.prepareStatement(
                "SELECT id FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL"
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java))
                } }
            }
            connection.prepareStatement(
                "UPDATE users SET password_hash=?,auth_version=auth_version+1,updated_at=clock_timestamp() WHERE id=?"
            ).use { statement ->
                statement.setString(1, replacement)
                statement.setObject(2, identity.userId)
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement(
                "UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE user_id=? AND revoked_at IS NULL"
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeUpdate()
            }
            sessions
        }
    }

    private fun lockUser(connection: Connection, identity: SessionIdentity): UserSelf? =
        connection.prepareStatement(
            """SELECT id,display_name,phone_e164,age_years,avatar_object_key,message_muted_until,created_at
               FROM users WHERE id=? AND deleted_at IS NULL AND auth_version=? FOR NO KEY UPDATE"""
        ).use { statement ->
            statement.setObject(1, identity.userId)
            statement.setInt(2, identity.authVersion)
            statement.executeQuery().use { rows -> if (!rows.next()) null else UserSelf(
                id = identity.userId.toString(),
                displayName = rows.getString("display_name"),
                avatarUrl = if (rows.getString("avatar_object_key") == null) null else avatarUrl(identity.userId),
                phone = rows.getString("phone_e164"),
                ageYears = rows.getInt("age_years"),
                messageMutedUntil = rows.getObject("message_muted_until", OffsetDateTime::class.java)?.toInstant()?.toString(),
                createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
            ) }
        }

    private fun sessionActive(
        connection: Connection,
        identity: SessionIdentity,
        allowLegacy: Boolean = false,
    ): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL
               AND expires_at>clock_timestamp() AND (auth_version=? OR (auth_version IS NULL AND ?)) FOR UPDATE"""
        ).use { statement ->
            statement.setObject(1, identity.sessionId)
            statement.setObject(2, identity.userId)
            statement.setInt(3, identity.authVersion)
            statement.setBoolean(4, allowLegacy)
            statement.executeQuery().use { it.next() }
        }

    private fun sessionExpired(): Nothing = throw ApiException(
        HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "Сеанс истёк. Войдите снова",
    )

    private fun invalidField(): Nothing = throw ApiException(
        HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректные данные",
    )

    private fun avatarStorageUnavailable(): Nothing = throw ApiException(
        HttpStatusCode.ServiceUnavailable, "AVATAR_STORAGE_UNAVAILABLE", "Хранилище фотографий недоступно",
    )
}
