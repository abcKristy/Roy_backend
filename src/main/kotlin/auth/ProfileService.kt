package com.example.auth

import com.example.api.ApiException
import com.example.persistence.DatabaseRuntime
import com.example.persistence.withRetryingJdbcTransaction
import com.example.storage.AvatarImage
import com.example.storage.AvatarStorageException
import com.example.storage.AvatarStore
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class UpdateProfileRequest(val displayName: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String) {
    override fun toString(): String = "ChangePasswordRequest(redacted)"
}

/** Profile mutations use the same users-first lock order as login and refresh. */
class ProfileService(
    private val database: DatabaseRuntime,
    private val passwordHashing: PasswordHashingWork,
    private val avatarStore: AvatarStore? = null,
) {
    /** Stages a processed object before any profile transaction. The key stays server-side. */
    internal suspend fun stageAvatar(image: AvatarImage): String {
        val store = avatarStore ?: avatarStorageUnavailable()
        return try {
            store.save(image)
        } catch (_: AvatarStorageException) {
            avatarStorageUnavailable()
        }
    }

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
                avatarUrl = if (rows.getString("avatar_object_key") == null) null else "/api/v1/users/${identity.userId}/avatar",
                phone = rows.getString("phone_e164"),
                ageYears = rows.getInt("age_years"),
                messageMutedUntil = rows.getObject("message_muted_until", OffsetDateTime::class.java)?.toInstant()?.toString(),
                createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
            ) }
        }

    private fun sessionActive(connection: Connection, identity: SessionIdentity): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL
               AND expires_at>clock_timestamp() AND auth_version=? FOR UPDATE"""
        ).use { statement ->
            statement.setObject(1, identity.sessionId)
            statement.setObject(2, identity.userId)
            statement.setInt(3, identity.authVersion)
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
