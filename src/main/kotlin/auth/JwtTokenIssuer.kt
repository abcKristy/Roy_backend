package com.example.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.Date
import java.util.UUID

class JwtTokenIssuer(secret: String) : TokenIssuer {
    private val algorithm: Algorithm

    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) { "JWT_SECRET must contain at least 32 UTF-8 bytes" }
        algorithm = Algorithm.HMAC256(secret)
    }

    override fun issue(userId: UUID, sessionId: UUID, authVersion: Int, issuedAt: Instant, expiresAt: Instant): String =
        JWT.create()
            .withSubject(userId.toString())
            .withJWTId(sessionId.toString())
            .withClaim("authVersion", authVersion)
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
}
