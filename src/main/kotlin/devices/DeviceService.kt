package com.example.devices

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
    val userId: UUID, val installationId: UUID, val platform: String, val token: String,
)

sealed interface RegisterDeviceResult {
    data class Registered(val value: DeviceRegistration) : RegisterDeviceResult
    data object InvalidToken : RegisterDeviceResult
    data object TokenInUse : RegisterDeviceResult
    data object UserUnavailable : RegisterDeviceResult
}

/** Serializes rare device changes and invalidates former owner's queued deliveries on rebind. */
class DeviceService(private val database: DatabaseRuntime, private val cipher: DeviceTokenCipher) {
    suspend fun register(command: RegisterDeviceCommand): RegisterDeviceResult {
        val token = command.token
        if (command.platform !in setOf("ANDROID_RUSTORE", "IOS_APNS") ||
            token.isEmpty() || token.toByteArray(StandardCharsets.UTF_8).size > 4096 ||
            token.any { it.isWhitespace() || Character.isISOControl(it) }) return RegisterDeviceResult.InvalidToken
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return try {
            database.withRetryingJdbcTransaction { connection ->
                if (!lockActor(connection, command.userId)) return@withRetryingJdbcTransaction RegisterDeviceResult.UserUnavailable
                lockRegistry(connection)
                val existing = findInstallation(connection, command.installationId)
                if (existing != null && existing.userId == command.userId && existing.platform == command.platform &&
                    existing.digest == digest && existing.revokedAt == null) {
                    return@withRetryingJdbcTransaction RegisterDeviceResult.Registered(
                        DeviceRegistration(command.installationId, existing.createdAt))
                }
                if (activeTokenElsewhere(connection, command.installationId, command.platform, digest)) {
                    return@withRetryingJdbcTransaction RegisterDeviceResult.TokenInUse
                }
                val now = databaseNow(connection)
                val ciphertext = cipher.encrypt(command.installationId, command.platform, token)
                if (existing == null) {
                    connection.prepareStatement(
                        """INSERT INTO device_registrations
                           (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at)
                           VALUES (?,?,?,?,?,?,?)"""
                    ).use { statement ->
                        statement.setObject(1, command.installationId)
                        statement.setObject(2, command.userId)
                        statement.setString(3, command.platform)
                        statement.setString(4, ciphertext)
                        statement.setString(5, digest)
                        statement.setObject(6, now.atOffset(ZoneOffset.UTC))
                        statement.setObject(7, now.atOffset(ZoneOffset.UTC))
                        check(statement.executeUpdate() == 1)
                    }
                } else {
                    if (existing.userId != command.userId) cancelQueuedJobs(connection, command.installationId)
                    connection.prepareStatement(
                        """UPDATE device_registrations SET user_id=?,platform=?,token_ciphertext=?,token_digest=?,
                           created_at=?,updated_at=?,revoked_at=NULL WHERE installation_id=?"""
                    ).use { statement ->
                        statement.setObject(1, command.userId)
                        statement.setString(2, command.platform)
                        statement.setString(3, ciphertext)
                        statement.setString(4, digest)
                        statement.setObject(5, (if (existing.userId == command.userId && existing.revokedAt == null)
                            existing.createdAt else now).atOffset(ZoneOffset.UTC))
                        statement.setObject(6, now.atOffset(ZoneOffset.UTC))
                        statement.setObject(7, command.installationId)
                        check(statement.executeUpdate() == 1)
                    }
                }
                RegisterDeviceResult.Registered(DeviceRegistration(
                    command.installationId, if (existing?.userId == command.userId && existing.revokedAt == null)
                        existing.createdAt else now))
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505") RegisterDeviceResult.TokenInUse else throw failure
        }
    }

    /** A different owner's installation is never modified and does not reveal its existence. */
    suspend fun revoke(userId: UUID, installationId: UUID) {
        database.withRetryingJdbcTransaction { connection ->
            if (!lockActor(connection, userId)) return@withRetryingJdbcTransaction
            lockRegistry(connection)
            val existing = findInstallation(connection, installationId)
            if (existing?.userId != userId || existing.revokedAt != null) return@withRetryingJdbcTransaction
            cancelQueuedJobs(connection, installationId)
            connection.prepareStatement(
                "UPDATE device_registrations SET revoked_at=clock_timestamp(),updated_at=clock_timestamp() WHERE installation_id=?"
            ).use { statement -> statement.setObject(1, installationId); check(statement.executeUpdate() == 1) }
        }
    }

    private data class StoredInstallation(
        val userId: UUID, val platform: String, val digest: String,
        val createdAt: Instant, val revokedAt: Instant?,
    )

    private fun lockActor(connection: Connection, userId: UUID): Boolean = connection.prepareStatement(
        "SELECT 1 FROM users WHERE id=? AND deleted_at IS NULL FOR NO KEY UPDATE"
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rows -> rows.next() }
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
        """SELECT user_id,platform,token_digest,created_at,revoked_at FROM device_registrations
           WHERE installation_id=? FOR UPDATE"""
    ).use { statement ->
        statement.setObject(1, installationId)
        statement.executeQuery().use { rows -> if (rows.next()) StoredInstallation(
            rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3),
            rows.getObject(4, OffsetDateTime::class.java).toInstant(),
            rows.getObject(5, OffsetDateTime::class.java)?.toInstant(),
        ) else null }
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
