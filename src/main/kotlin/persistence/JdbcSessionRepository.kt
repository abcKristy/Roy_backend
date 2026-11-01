package com.example.persistence

import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.RefreshRepository
import com.example.auth.RefreshRotationResult
import com.example.auth.UserSelf
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class JdbcSessionRepository(private val database: DatabaseRuntime) : SessionRepository, RefreshRepository {
    override suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf? =
        database.withJdbcTransaction { connection ->
            connection.prepareStatement(
                """SELECT u.id, u.display_name, u.phone_e164, u.age_years, u.avatar_object_key,
                          u.message_muted_until, u.created_at
                   FROM auth_sessions s JOIN users u ON u.id = s.user_id
                   WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL
                     AND s.expires_at > ? AND u.deleted_at IS NULL AND u.auth_version = ?"""
            ).use { statement ->
                statement.setObject(1, identity.sessionId)
                statement.setObject(2, identity.userId)
                statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                statement.setInt(4, identity.authVersion)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toUserSelf() else null }
            }
        }

    override suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean =
        database.withJdbcTransaction { connection ->
            connection.prepareStatement(
                """UPDATE auth_sessions s SET revoked_at = ?
                   FROM users u WHERE s.id = ? AND s.user_id = ? AND u.id = s.user_id
                     AND s.revoked_at IS NULL AND s.expires_at > ?
                     AND u.deleted_at IS NULL AND u.auth_version = ?"""
            ).use { statement ->
                statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                statement.setObject(2, identity.sessionId)
                statement.setObject(3, identity.userId)
                statement.setObject(4, now.atOffset(ZoneOffset.UTC))
                statement.setInt(5, identity.authVersion)
                statement.executeUpdate() == 1
            }
        }

    override suspend fun rotate(
        sessionId: UUID,
        presentedHash: String,
        replacementHash: String,
        now: Instant,
    ): RefreshRotationResult = database.withJdbcTransaction { connection ->
        val userId = connection.prepareStatement("SELECT user_id FROM auth_sessions WHERE id = ?").use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: return@withJdbcTransaction RefreshRotationResult.Invalid
        // User-first lock order matches account updates and serializes auth_version changes.
        val userExists = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { it.next() }
        }
        if (!userExists) return@withJdbcTransaction RefreshRotationResult.Invalid

        val current = connection.prepareStatement(
            """SELECT s.id, s.user_id, s.expires_at, s.revoked_at, s.auth_version AS session_auth_version,
                      s.refresh_token_hash, s.previous_refresh_token_hash,
                      u.id, u.display_name, u.phone_e164, u.age_years, u.avatar_object_key,
                      u.message_muted_until, u.created_at, u.auth_version AS user_auth_version
               FROM auth_sessions s JOIN users u ON u.id = s.user_id
               WHERE s.id = ? AND u.deleted_at IS NULL FOR UPDATE OF s"""
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else StoredRefreshSession(
                    userId = rows.getObject("user_id", UUID::class.java),
                    authVersion = rows.getInt("session_auth_version").takeUnless { rows.wasNull() },
                    userAuthVersion = rows.getInt("user_auth_version"),
                    expiresAt = rows.getTimestamp("expires_at").toInstant(),
                    revoked = rows.getTimestamp("revoked_at") != null,
                    currentHash = rows.getString("refresh_token_hash"),
                    previousHash = rows.getString("previous_refresh_token_hash"),
                    user = rows.toUserSelf(),
                )
            }
        } ?: return@withJdbcTransaction RefreshRotationResult.Invalid

        if (current.revoked || !current.expiresAt.isAfter(now) ||
            current.authVersion == null || current.authVersion != current.userAuthVersion ||
            current.currentHash == null
        ) return@withJdbcTransaction RefreshRotationResult.Invalid

        if (presentedHash == current.previousHash) {
            connection.prepareStatement("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?").use { statement ->
                statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                statement.setObject(2, sessionId)
                statement.executeUpdate()
            }
            return@withJdbcTransaction RefreshRotationResult.Replayed
        }
        if (presentedHash != current.currentHash) return@withJdbcTransaction RefreshRotationResult.Invalid

        connection.prepareStatement(
            """UPDATE auth_sessions SET previous_refresh_token_hash = refresh_token_hash,
                      refresh_token_hash = ? WHERE id = ?"""
        ).use { statement ->
            statement.setString(1, replacementHash)
            statement.setObject(2, sessionId)
            statement.executeUpdate()
        }
        RefreshRotationResult.Rotated(current.user, current.userId, current.authVersion, current.expiresAt)
    }

    private data class StoredRefreshSession(
        val userId: UUID,
        val authVersion: Int?,
        val userAuthVersion: Int,
        val expiresAt: Instant,
        val revoked: Boolean,
        val currentHash: String?,
        val previousHash: String?,
        val user: UserSelf,
    )

    private fun ResultSet.toUserSelf(): UserSelf {
        val id = getObject("id", UUID::class.java)
        return UserSelf(
            id = id.toString(),
            displayName = getString("display_name"),
            avatarUrl = if (getString("avatar_object_key") == null) null else "/api/v1/users/$id/avatar",
            phone = getString("phone_e164"),
            ageYears = getInt("age_years"),
            messageMutedUntil = getTimestamp("message_muted_until")?.toInstant()?.toString(),
            createdAt = getTimestamp("created_at").toInstant().toString(),
        )
    }
}
