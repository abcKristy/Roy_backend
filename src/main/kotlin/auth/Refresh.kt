package com.example.auth

import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

@Serializable
data class RefreshRequest(val refreshToken: String) {
    override fun toString(): String = "RefreshRequest(redacted)"
}

sealed interface RefreshRotationResult {
    data class Rotated(
        val user: UserSelf,
        val userId: UUID,
        val authVersion: Int,
        val sessionExpiresAt: Instant,
    ) : RefreshRotationResult

    data object Replayed : RefreshRotationResult
    data object Invalid : RefreshRotationResult
}

fun interface RefreshRepository {
    suspend fun rotate(
        sessionId: UUID,
        presentedHash: String,
        replacementHash: String,
        now: Instant,
    ): RefreshRotationResult
}

/** Opaque 256-bit secret. Only a SHA-256 digest of the complete token is persisted. */
object RefreshTokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val format = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([A-Za-z0-9_-]{43})$")

    fun create(sessionId: UUID): String {
        val secret = ByteArray(32).also(random::nextBytes)
        return "$sessionId.${encoder.encodeToString(secret)}"
    }

    fun sessionId(token: String): UUID? {
        val match = format.matchEntire(token) ?: return null
        val secret = try { decoder.decode(match.groupValues[2]) } catch (_: IllegalArgumentException) { return null }
        if (secret.size != 32 || encoder.encodeToString(secret) != match.groupValues[2]) return null
        return UUID.fromString(match.groupValues[1])
    }

    fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }
}

class RefreshService(
    private val repository: RefreshRepository,
    private val tokenIssuer: TokenIssuer,
    private val clock: Clock = Clock.systemUTC(),
    private val accessTtl: Duration = Duration.ofMinutes(15),
) {
    init {
        require(accessTtl.nano == 0 && accessTtl.seconds in 60..30L * 24 * 60 * 60)
    }

    suspend fun refresh(request: RefreshRequest): Session {
        val sessionId = RefreshTokens.sessionId(request.refreshToken) ?: expired()
        val replacement = RefreshTokens.create(sessionId)
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val result = repository.rotate(
            sessionId, RefreshTokens.hash(request.refreshToken), RefreshTokens.hash(replacement), now,
        )
        if (result !is RefreshRotationResult.Rotated) expired()
        val accessExpiresAt = minOf(now.plus(accessTtl), result.sessionExpiresAt)
        if (!accessExpiresAt.isAfter(now)) expired()
        return Session(
            accessToken = tokenIssuer.issue(result.userId, sessionId, result.authVersion, now, accessExpiresAt),
            expiresAt = accessExpiresAt.toString(),
            refreshToken = replacement,
            refreshExpiresAt = result.sessionExpiresAt.toString(),
            user = result.user,
        )
    }

    private fun expired(): Nothing = throw ApiException(
        HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "Сеанс истёк. Войдите снова",
    )
}
