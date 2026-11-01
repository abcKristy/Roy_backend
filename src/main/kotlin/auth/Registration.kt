package com.example.auth

import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class RegisterRequest(
    val phone: String,
    val password: String,
    val displayName: String,
    val ageYears: Int,
    val acceptedPrivacyPolicyVersion: String,
) {
    override fun toString(): String = "RegisterRequest(redacted)"
}

@Serializable
data class UserSelf(
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val phone: String,
    val ageYears: Int,
    val messageMutedUntil: String? = null,
    val createdAt: String,
) {
    override fun toString(): String = "UserSelf(redacted)"
}

@Serializable
data class Session(
    val accessToken: String,
    val expiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
    val user: UserSelf,
) {
    override fun toString(): String = "Session(redacted)"
}

/** Immutable process snapshot. Publishing a new version requires draining and restarting the server. */
sealed interface PublishedPrivacyPolicy {
    val version: String?
}

object UnpublishedPrivacyPolicy : PublishedPrivacyPolicy {
    override val version: String? = null
}

class ActivePrivacyPolicy(override val version: String) : PublishedPrivacyPolicy {
    init {
        require(version.isNotBlank())
    }
}

data class NewRegistration(
    val userId: UUID,
    val sessionId: UUID,
    val phone: String,
    val passwordHash: String,
    val displayName: String,
    val ageYears: Int,
    val privacyVersion: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val refreshTokenHash: String,
) {
    override fun toString(): String = "NewRegistration(redacted)"
}

class DuplicatePhoneException : RuntimeException()

fun interface RegistrationRepository {
    suspend fun create(registration: NewRegistration)
}

fun interface TokenIssuer {
    fun issue(userId: UUID, sessionId: UUID, authVersion: Int, issuedAt: Instant, expiresAt: Instant): String
}

class RegistrationService(
    private val policy: PublishedPrivacyPolicy,
    private val passwordHashing: PasswordHashingWork,
    private val repository: RegistrationRepository,
    private val tokenIssuer: TokenIssuer,
    private val clock: Clock = Clock.systemUTC(),
    private val accessTtl: Duration = Duration.ofMinutes(15),
    private val refreshTtl: Duration = Duration.ofDays(30),
) {
    init {
        require(accessTtl.nano == 0 && accessTtl.seconds in 60..30L * 24 * 60 * 60)
        require(refreshTtl.nano == 0 && refreshTtl.seconds in 60..30L * 24 * 60 * 60)
    }

    suspend fun register(request: RegisterRequest): Session {
        val currentVersion = policy.version
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Регистрация временно недоступна")
        if (request.acceptedPrivacyPolicyVersion != currentVersion) {
            throw ApiException(
                HttpStatusCode.UnprocessableEntity,
                "PRIVACY_POLICY_ACCEPTANCE_REQUIRED",
                "Примите актуальную политику конфиденциальности",
                buildJsonObject { put("currentVersion", currentVersion) },
            )
        }

        val phone = request.phone.trim()
        val displayName = request.displayName.trim()
        if (!PHONE.matches(phone) || displayName.codePointCount(0, displayName.length) !in 1..80 ||
            request.password.codePointCount(0, request.password.length) !in 8..128 ||
            request.ageYears !in 12..Short.MAX_VALUE.toInt()
        ) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректные данные")
        }

        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val refreshExpiresAt = now.plus(refreshTtl)
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val refreshToken = RefreshTokens.create(sessionId)
        val passwordHash = passwordHashing.hash(request.password)
        val registration = NewRegistration(
            userId, sessionId, phone, passwordHash, displayName, request.ageYears,
            currentVersion, now, refreshExpiresAt, RefreshTokens.hash(refreshToken),
        )
        val accessExpiresAt = minOf(now.plus(accessTtl), refreshExpiresAt)
        val token = tokenIssuer.issue(userId, sessionId, 0, now, accessExpiresAt)
        try {
            repository.create(registration)
        } catch (_: DuplicatePhoneException) {
            throw ApiException(HttpStatusCode.Conflict, "DUPLICATE_PHONE", "Номер телефона уже зарегистрирован")
        }
        return Session(
            accessToken = token,
            expiresAt = accessExpiresAt.toString(),
            refreshToken = refreshToken,
            refreshExpiresAt = refreshExpiresAt.toString(),
            user = UserSelf(
                id = userId.toString(),
                displayName = displayName,
                phone = phone,
                ageYears = request.ageYears,
                createdAt = now.toString(),
            ),
        )
    }

    private companion object {
        val PHONE = Regex("^\\+[1-9][0-9]{1,14}$")
    }
}
