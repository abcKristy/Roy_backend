package com.example.persistence

import com.example.auth.LoginCredential
import com.example.auth.LoginRepository
import com.example.auth.NewLoginSession
import com.example.auth.UserSelf
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class JdbcLoginRepository(private val database: DatabaseRuntime) : LoginRepository {
    override suspend fun findCredential(phone: String): LoginCredential? = database.withJdbcTransaction { connection ->
        connection.prepareStatement(
            "SELECT id, password_hash, auth_version FROM users WHERE phone_e164 = ? AND deleted_at IS NULL"
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rows ->
                if (rows.next()) LoginCredential(
                    rows.getObject("id", UUID::class.java),
                    rows.getString("password_hash"),
                    rows.getInt("auth_version"),
                ) else null
            }
        }
    }

    override suspend fun createSession(session: NewLoginSession): UserSelf? = database.withJdbcTransaction { connection ->
        val user = readLockedUser(connection, session.userId) ?: return@withJdbcTransaction null
        if (user.passwordHash != session.expectedPasswordHash || user.authVersion != session.expectedAuthVersion) {
            return@withJdbcTransaction null
        }
        if (session.replacementPasswordHash != null) {
            connection.prepareStatement("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?").use { statement ->
                statement.setString(1, session.replacementPasswordHash)
                statement.setObject(2, session.createdAt.atOffset(ZoneOffset.UTC))
                statement.setObject(3, session.userId)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """INSERT INTO auth_sessions (id, user_id, created_at, expires_at, auth_version, refresh_token_hash)
               VALUES (?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, session.sessionId)
            statement.setObject(2, session.userId)
            statement.setObject(3, session.createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(4, session.expiresAt.atOffset(ZoneOffset.UTC))
            statement.setInt(5, session.expectedAuthVersion)
            statement.setString(6, session.refreshTokenHash)
            statement.executeUpdate()
        }
        user.toUserSelf()
    }

    private fun readLockedUser(connection: Connection, id: UUID): StoredUser? = connection.prepareStatement(
        """SELECT id, phone_e164, password_hash, auth_version, display_name, age_years,
                  avatar_object_key, message_muted_until, created_at
           FROM users WHERE id = ? AND deleted_at IS NULL FOR UPDATE"""
    ).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) rows.toStoredUser() else null }
    }

    private fun ResultSet.toStoredUser(): StoredUser = StoredUser(
        id = getObject("id", UUID::class.java),
        phone = getString("phone_e164"),
        passwordHash = getString("password_hash"),
        authVersion = getInt("auth_version"),
        displayName = getString("display_name"),
        ageYears = getInt("age_years"),
        avatarObjectKey = getString("avatar_object_key"),
        messageMutedUntil = getTimestamp("message_muted_until")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private class StoredUser(
        val id: UUID,
        val phone: String,
        val passwordHash: String,
        val authVersion: Int,
        val displayName: String,
        val ageYears: Int,
        val avatarObjectKey: String?,
        val messageMutedUntil: Instant?,
        val createdAt: Instant,
    ) {
        fun toUserSelf() = UserSelf(
            id = id.toString(),
            displayName = displayName,
            avatarUrl = if (avatarObjectKey == null) null else "/api/v1/users/$id/avatar",
            phone = phone,
            ageYears = ageYears,
            messageMutedUntil = messageMutedUntil?.toString(),
            createdAt = createdAt.toString(),
        )
    }
}
