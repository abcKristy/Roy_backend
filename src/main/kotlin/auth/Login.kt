package com.example.auth

import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class LoginRequest(val phone: String, val password: String) {
    override fun toString(): String = "LoginRequest(redacted)"
}

/** A credential snapshot. The repository rechecks it under the user row lock before issuing a session. */
data class LoginCredential(
    val userId: UUID,
    val passwordHash: String,
    val authVersion: Int,
) {
    override fun toString(): String = "LoginCredential(redacted)"
}

data class NewLoginSession(
    val userId: UUID,
    val sessionId: UUID,
    val expectedPasswordHash: String,
    val expectedAuthVersion: Int,
    val replacementPasswordHash: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val refreshTokenHash: String,
) {
    override fun toString(): String = "NewLoginSession(redacted)"
}

interface LoginRepository {
    suspend fun findCredential(phone: String): LoginCredential?

    /** Returns the current profile or null if deletion/password change raced with verification. */
    suspend fun createSession(session: NewLoginSession): UserSelf?
}

class LoginService(
    private val repository: LoginRepository,
    private val passwordHashing: PasswordHashingWork,
    private val tokenIssuer: TokenIssuer,
    private val clock: Clock = Clock.systemUTC(),
    private val accessTtl: Duration = Duration.ofMinutes(15),
    private val refreshTtl: Duration = Duration.ofDays(30),
) {
    init {
        require(accessTtl.nano == 0 && accessTtl.seconds in 60..30L * 24 * 60 * 60)
        require(refreshTtl.nano == 0 && refreshTtl.seconds in 60..30L * 24 * 60 * 60)
    }

    suspend fun login(request: LoginRequest): Session {
        val phone = request.phone.trim()
        val credential = if (PHONE.matches(phone)) repository.findCredential(phone) else null
        if (credential == null) {
            passwordHashing.verifyUnknown(request.password)
            invalidCredentials()
        }
        if (!passwordHashing.verify(request.password, credential.passwordHash)) invalidCredentials()

        val replacement = if (passwordHashing.needsRehash(credential.passwordHash)) {
            passwordHashing.hash(request.password)
        } else {
            null
        }
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val refreshExpiresAt = now.plus(refreshTtl)
        val sessionId = UUID.randomUUID()
        val refreshToken = RefreshTokens.create(sessionId)
        val newSession = NewLoginSession(
            credential.userId, sessionId, credential.passwordHash, credential.authVersion,
            replacement, now, refreshExpiresAt, RefreshTokens.hash(refreshToken),
        )
        val user = repository.createSession(newSession) ?: invalidCredentials()
        val accessExpiresAt = minOf(now.plus(accessTtl), refreshExpiresAt)
        return Session(
            accessToken = tokenIssuer.issue(credential.userId, sessionId, credential.authVersion, now, accessExpiresAt),
            expiresAt = accessExpiresAt.toString(),
            refreshToken = refreshToken,
            refreshExpiresAt = refreshExpiresAt.toString(),
            user = user,
        )
    }

    private fun invalidCredentials(): Nothing = throw ApiException(
        HttpStatusCode.Unauthorized,
        "INVALID_CREDENTIALS",
        "Неверный номер телефона или пароль",
    )

    private companion object {
        val PHONE = Regex("^\\+[1-9][0-9]{1,14}$")
    }
}
