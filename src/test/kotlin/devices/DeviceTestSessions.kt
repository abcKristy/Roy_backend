package com.example.devices

import com.example.auth.SessionIdentity
import java.sql.Connection
import java.util.UUID

internal fun Connection.seedDeviceSession(userId: UUID): SessionIdentity {
    val sessionId = UUID.randomUUID()
    prepareStatement(
        """INSERT INTO auth_sessions (id,user_id,created_at,expires_at)
           VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 hour')"""
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.setObject(2, userId)
        check(statement.executeUpdate() == 1)
    }
    return SessionIdentity(userId, sessionId, authVersion = 0)
}
