package com.example.persistence

import com.example.auth.SessionIdentity
import com.example.auth.SessionRepository
import com.example.auth.UserSelf
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class JdbcSessionRepository(private val database: DatabaseRuntime) : SessionRepository {
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
