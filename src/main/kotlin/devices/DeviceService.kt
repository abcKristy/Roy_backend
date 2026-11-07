package com.example.devices

import com.example.auth.SessionIdentity
import com.example.persistence.DatabaseRuntime
import com.example.persistence.withRetryingJdbcTransaction
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encryption key comes from process configuration, never from a database row. */
class DeviceTokenCipher private constructor(key: ByteArray) {
    private val secret = SecretKeySpec(key.copyOf(), "AES")
    private val random = SecureRandom()

    fun encrypt(installationId: UUID, platform: String, token: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(installationId, platform))
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        return "v1:" + Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    fun decrypt(installationId: UUID, platform: String, ciphertext: String): String {
        require(ciphertext.startsWith("v1:")) { "Unsupported push token ciphertext version" }
        val bytes = Base64.getDecoder().decode(ciphertext.removePrefix("v1:"))
        require(bytes.size >= 12 + 16) { "Invalid push token ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(installationId, platform))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun aad(installationId: UUID, platform: String): ByteArray =
        "$installationId:$platform".toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun fromBase64(value: String): DeviceTokenCipher {
            val bytes = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("PUSH_TOKEN_ENCRYPTION_KEY_BASE64 is invalid")
            }
            require(bytes.size == 32) { "PUSH_TOKEN_ENCRYPTION_KEY_BASE64 must encode 32 bytes" }
            return DeviceTokenCipher(bytes)
        }
    }
}

data class DeviceRegistration(val installationId: UUID, val registeredAt: Instant)
data class RegisterDeviceCommand(
    val identity: SessionIdentity, val installationId: UUID, val platform: String, val token: String,
)

sealed interface RegisterDeviceResult {
    data class Registered(val value: DeviceRegistration) : RegisterDeviceResult
    data object InvalidToken : RegisterDeviceResult
    data object TokenInUse : RegisterDeviceResult
    data object StaleRegistration : RegisterDeviceResult
    data object SessionUnavailable : RegisterDeviceResult
}

/** Serializes rare device changes and invalidates former owner's queued deliveries on rebind. */
class DeviceService(private val database: DatabaseRuntime, private val cipher: DeviceTokenCipher) {
    suspend fun register(command: RegisterDeviceCommand): RegisterDeviceResult {
        val identity = command.identity
        val token = command.token
        if (command.platform !in setOf("ANDROID_RUSTORE", "IOS_APNS") ||
            token.isEmpty() || token.toByteArray(StandardCharsets.UTF_8).size > 4096 ||
            token.any { it.isWhitespace() || Character.isISOControl(it) }) return RegisterDeviceResult.InvalidToken
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return try {
            database.withRetryingJdbcTransaction { connection ->
                if (!lockActor(connection, identity)) return@withRetryingJdbcTransaction RegisterDeviceResult.SessionUnavailable
                lockRegistry(connection)
                val sessionOrder = lockActiveSessionOrder(connection, identity)
                    ?: return@withRetryingJdbcTransaction RegisterDeviceResult.SessionUnavailable
                val existing = findInstallation(connection, command.installationId)
                if (existing?.isNewerThan(sessionOrder) == true ||
                    isRetiredToken(connection, command.installationId, command.platform, digest)) {
                    return@withRetryingJdbcTransaction RegisterDeviceResult.StaleRegistration
                }
                val sameOwner = existing?.userId == identity.userId
                val sameToken = existing?.platform == command.platform && existing.digest == digest
                if (existing != null && sameOwner && sameToken && existing.revokedAt == null) {
                    if (existing.lastSessionOrder != sessionOrder) {
                        updateSessionOrder(connection, command.installationId, sessionOrder)
                    }
                    return@withRetryingJdbcTransaction RegisterDeviceResult.Registered(
                        DeviceRegistration(command.installationId, existing.createdAt))
                }
                if (activeTokenElsewhere(connection, command.installationId, command.platform, digest)) {
                    return@withRetryingJdbcTransaction RegisterDeviceResult.TokenInUse
                }
                val now = databaseNow(connection)
                val registeredAt = if (existing != null && sameOwner && existing.revokedAt == null) existing.createdAt else now
                val ciphertext = cipher.encrypt(command.installationId, command.platform, token)
                if (existing == null) {
                    connection.prepareStatement(
                        """INSERT INTO device_registrations
                           (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at,last_session_order)
                           VALUES (?,?,?,?,?,?,?,?)"""
                    ).use { statement ->
                        statement.setObject(1, command.installationId)
                        statement.setObject(2, identity.userId)
                        statement.setString(3, command.platform)
                        statement.setString(4, ciphertext)
                        statement.setString(5, digest)
                        statement.setObject(6, now.atOffset(ZoneOffset.UTC))
                        statement.setObject(7, now.atOffset(ZoneOffset.UTC))
                        statement.setLong(8, sessionOrder)
                        check(statement.executeUpdate() == 1)
                    }
                } else {
                    if (!sameOwner) cancelQueuedJobs(connection, command.installationId)
                    if (!sameToken) retireToken(connection, command.installationId, existing)
                    connection.prepareStatement(
                        """UPDATE device_registrations SET user_id=?,platform=?,token_ciphertext=?,token_digest=?,
                           created_at=?,updated_at=?,revoked_at=NULL,last_session_order=? WHERE installation_id=?"""
                    ).use { statement ->
                        statement.setObject(1, identity.userId)
                        statement.setString(2, command.platform)
                        statement.setString(3, ciphertext)
                        statement.setString(4, digest)
                        statement.setObject(5, registeredAt.atOffset(ZoneOffset.UTC))
                        statement.setObject(6, now.atOffset(ZoneOffset.UTC))
                        statement.setLong(7, sessionOrder)
                        statement.setObject(8, command.installationId)
                        check(statement.executeUpdate() == 1)
                    }
                }
                RegisterDeviceResult.Registered(DeviceRegistration(command.installationId, registeredAt))
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505") RegisterDeviceResult.TokenInUse else throw failure
        }
    }

    /** False means the session is no longer active. Missing or foreign installations are a no-op. */
    suspend fun revoke(identity: SessionIdentity, installationId: UUID): Boolean =
        database.withRetryingJdbcTransaction { connection ->
            if (!lockActor(connection, identity)) return@withRetryingJdbcTransaction false
            lockRegistry(connection)
            val sessionOrder = lockActiveSessionOrder(connection, identity) ?: return@withRetryingJdbcTransaction false
            val existing = findInstallation(connection, installationId)
            if (existing?.userId != identity.userId || existing.isNewerThan(sessionOrder)) return@withRetryingJdbcTransaction true
            if (existing.revokedAt != null) {
                if (existing.lastSessionOrder != sessionOrder) updateSessionOrder(connection, installationId, sessionOrder)
                return@withRetryingJdbcTransaction true
            }
            cancelQueuedJobs(connection, installationId)
            connection.prepareStatement(
                """UPDATE device_registrations SET revoked_at=clock_timestamp(),updated_at=clock_timestamp(),
                   last_session_order=? WHERE installation_id=?"""
            ).use { statement ->
                statement.setLong(1, sessionOrder)
                statement.setObject(2, installationId)
                check(statement.executeUpdate() == 1)
            }
            true
        }

    private data class StoredInstallation(
        val userId: UUID, val platform: String, val digest: String,
        val createdAt: Instant, val revokedAt: Instant?, val lastSessionOrder: Long?,
    ) {
        fun isNewerThan(sessionOrder: Long): Boolean = lastSessionOrder?.let { it > sessionOrder } ?: false
    }

    private fun lockActor(connection: Connection, identity: SessionIdentity): Boolean = connection.prepareStatement(
        "SELECT 1 FROM users WHERE id=? AND auth_version=? AND deleted_at IS NULL FOR NO KEY UPDATE"
    ).use { statement ->
        statement.setObject(1, identity.userId)
        statement.setInt(2, identity.authVersion)
        statement.executeQuery().use { rows -> rows.next() }
    }

    // Lock the session through commit so logout and device mutation cannot pass each other.
    private fun lockActiveSessionOrder(connection: Connection, identity: SessionIdentity): Long? {
        val session = connection.prepareStatement(
            """SELECT device_registration_order,expires_at FROM auth_sessions
               WHERE id=? AND user_id=? AND revoked_at IS NULL FOR NO KEY UPDATE"""
        ).use { statement ->
            statement.setObject(1, identity.sessionId)
            statement.setObject(2, identity.userId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getLong(1) to rows.getObject(2, OffsetDateTime::class.java).toInstant() else null
            }
        } ?: return null
        return session.first.takeIf { session.second.isAfter(databaseNow(connection)) }
    }

    private fun lockRegistry(connection: Connection) {
        val key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
            .digest("device-registry:global".toByteArray(StandardCharsets.UTF_8))).long
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, key)
            statement.executeQuery().use { rows -> check(rows.next()) }
        }
    }

    private fun findInstallation(connection: Connection, installationId: UUID): StoredInstallation? = connection.prepareStatement(
        """SELECT user_id,platform,token_digest,created_at,revoked_at,last_session_order FROM device_registrations
           WHERE installation_id=? FOR UPDATE"""
    ).use { statement ->
        statement.setObject(1, installationId)
        statement.executeQuery().use { rows -> if (rows.next()) StoredInstallation(
            rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3),
            rows.getObject(4, OffsetDateTime::class.java).toInstant(),
            rows.getObject(5, OffsetDateTime::class.java)?.toInstant(),
            rows.getObject(6, Long::class.javaObjectType),
        ) else null }
    }

    private fun updateSessionOrder(connection: Connection, installationId: UUID, sessionOrder: Long) {
        connection.prepareStatement("UPDATE device_registrations SET last_session_order=? WHERE installation_id=?")
            .use { statement ->
                statement.setLong(1, sessionOrder)
                statement.setObject(2, installationId)
                check(statement.executeUpdate() == 1)
            }
    }

    private fun isRetiredToken(connection: Connection, installationId: UUID, platform: String, digest: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM device_retired_tokens WHERE installation_id=? AND platform=? AND token_digest=?"
        ).use { statement ->
            statement.setObject(1, installationId)
            statement.setString(2, platform)
            statement.setString(3, digest)
            statement.executeQuery().use { rows -> rows.next() }
        }

    private fun retireToken(connection: Connection, installationId: UUID, existing: StoredInstallation) {
        connection.prepareStatement(
            "INSERT INTO device_retired_tokens (installation_id,platform,token_digest) VALUES (?,?,?)"
        ).use { statement ->
            statement.setObject(1, installationId)
            statement.setString(2, existing.platform)
            statement.setString(3, existing.digest)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun activeTokenElsewhere(connection: Connection, installationId: UUID, platform: String, digest: String): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM device_registrations WHERE platform=? AND token_digest=?
               AND installation_id<>? AND revoked_at IS NULL"""
        ).use { statement ->
            statement.setString(1, platform)
            statement.setString(2, digest)
            statement.setObject(3, installationId)
            statement.executeQuery().use { rows -> rows.next() }
        }

    private fun cancelQueuedJobs(connection: Connection, installationId: UUID) {
        connection.prepareStatement(
            """UPDATE push_delivery_jobs SET status='CANCELLED',claim_token=NULL,lease_until=NULL,
               completed_at=clock_timestamp()
               WHERE installation_id=? AND status IN ('PENDING','PROCESSING')"""
        ).use { statement -> statement.setObject(1, installationId); statement.executeUpdate() }
    }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
        }
    }
}
