package com.example.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class SessionIdentity(val userId: UUID, val sessionId: UUID, val authVersion: Int)

interface SessionRepository {
    suspend fun findActive(identity: SessionIdentity, now: Instant): UserSelf?
    suspend fun revoke(identity: SessionIdentity, now: Instant): Boolean
}

/** Cryptographic verification is followed by a fresh database check on every protected request. */
class SessionAccessService(
    secret: String,
    private val repository: SessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val verifier = (JWT.require(Algorithm.HMAC256(secret.also {
        require(it.toByteArray(Charsets.UTF_8).size >= 32) { "JWT_SECRET must contain at least 32 UTF-8 bytes" }
    })) as JWTVerifier.BaseVerification).build(clock)

    suspend fun currentUser(authorization: String?): UserSelf {
        val identity = authenticate(authorization)
        return repository.findActive(identity, clock.instant()) ?: expired()
    }

    suspend fun logout(authorization: String?) {
        val identity = authenticate(authorization)
        if (!repository.revoke(identity, clock.instant())) expired()
    }

    private fun authenticate(authorization: String?): SessionIdentity {
        if (authorization == null) throw ApiException(
            HttpStatusCode.Unauthorized, "AUTH_REQUIRED", "Требуется вход в аккаунт",
        )
        val token = authorization.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() && !it.contains(' ') }
            ?: expired()
        val jwt = try {
            verifier.verify(token)
        } catch (_: JWTVerificationException) {
            expired()
        } catch (_: IllegalArgumentException) {
            expired()
        }
        val userId = jwt.subject?.let(::parseUuid) ?: expired()
        val sessionId = jwt.id?.let(::parseUuid) ?: expired()
        val version = jwt.getClaim("authVersion").asInt()?.takeIf { it >= 0 } ?: expired()
        val issuedAt = jwt.issuedAt?.toInstant() ?: expired()
        val expiresAt = jwt.expiresAt?.toInstant() ?: expired()
        val now = clock.instant()
        if (issuedAt.isAfter(now) || !expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt)) expired()
        return SessionIdentity(userId, sessionId, version)
    }

    private fun parseUuid(raw: String): UUID? = try {
        UUID.fromString(raw).takeIf { it.toString() == raw }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun expired(): Nothing = throw ApiException(
        HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "Сеанс истёк. Войдите снова",
    )
}
