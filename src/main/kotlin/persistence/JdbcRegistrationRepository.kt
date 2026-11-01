package com.example.persistence

import com.example.auth.DuplicatePhoneException
import com.example.auth.NewRegistration
import com.example.auth.RegistrationRepository
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JdbcRegistrationRepository(private val database: DatabaseRuntime) : RegistrationRepository {
    override suspend fun create(registration: NewRegistration) {
        try {
            database.withJdbcTransaction { connection ->
                insertUser(connection, registration)
                insertSession(connection, registration)
                insertNotificationSettings(connection, registration)
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505" &&
                (failure as? PSQLException)?.serverErrorMessage?.constraint == "users_phone_e164_unique"
            ) {
                throw DuplicatePhoneException()
            }
            throw failure
        }
    }

    private fun insertUser(connection: Connection, value: NewRegistration) {
        connection.prepareStatement(
            """INSERT INTO users
                (id, phone_e164, password_hash, display_name, age_years, accepted_privacy_version,
                 accepted_privacy_at, auth_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, value.userId)
            statement.setString(2, value.phone)
            statement.setString(3, value.passwordHash)
            statement.setString(4, value.displayName)
            statement.setInt(5, value.ageYears)
            statement.setString(6, value.privacyVersion)
            statement.setObject(7, value.createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(8, value.createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(9, value.createdAt.atOffset(ZoneOffset.UTC))
            statement.executeUpdate()
        }
    }

    private fun insertSession(connection: Connection, value: NewRegistration) {
        connection.prepareStatement(
            "INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, value.sessionId)
            statement.setObject(2, value.userId)
            statement.setObject(3, value.createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(4, value.expiresAt.atOffset(ZoneOffset.UTC))
            statement.executeUpdate()
        }
    }

    private fun insertNotificationSettings(connection: Connection, value: NewRegistration) {
        connection.prepareStatement(
            """INSERT INTO notification_settings
                (user_id, reminders_push_enabled, personal_push_enabled, extra_push_enabled,
                 extra_in_app_enabled, revision, updated_at)
                VALUES (?, true, true, false, false, 0, ?)"""
        ).use { statement ->
            statement.setObject(1, value.userId)
            statement.setObject(2, value.createdAt.atOffset(ZoneOffset.UTC))
            statement.executeUpdate()
        }
    }
}
