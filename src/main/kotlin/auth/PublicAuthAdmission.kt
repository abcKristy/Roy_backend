package com.example.auth

import com.example.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Semaphore
import java.security.MessageDigest
import java.util.Base64

/** One in-flight public attempt leaves another hashing slot for authenticated operations. */
internal class PublicAuthAdmission {
    private val slot = Semaphore(1)
    private val login = PublicAuthRateLimiter(processLimit = 60, phoneLimit = 8)
    private val registration = PublicAuthRateLimiter(processLimit = 12, phoneLimit = 3)

    suspend fun <T> login(phone: String?, attempt: suspend () -> T): T = run(
        phone, login, "LOGIN_RATE_LIMITED", "Слишком много попыток входа. Повторите позже", attempt,
    )

    suspend fun <T> register(phone: String, attempt: suspend () -> T): T = run(
        phone, registration, "REGISTRATION_RATE_LIMITED", "Слишком много попыток регистрации. Повторите позже", attempt,
    )

    private suspend fun <T> run(
        phone: String?,
        limiter: PublicAuthRateLimiter,
        code: String,
        message: String,
        attempt: suspend () -> T,
    ): T {
        fun limited(retryAfter: Int): Nothing = throw ApiException(
            HttpStatusCode.TooManyRequests, code, message, retryAfterSeconds = retryAfter,
        )

        if (!slot.tryAcquire()) limited(1)
        try {
            limiter.recordAttempt(phone)?.let(::limited)
            return attempt()
        } finally {
            slot.release()
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val WINDOW_NANOS = 60 * NANOS_PER_SECOND

/** Called under the admission slot. The process limit bounds the number of remembered phone keys. */
private class PublicAuthRateLimiter(private val processLimit: Int, private val phoneLimit: Int) {
    private val attemptsByPhone = mutableMapOf<String, Int>()
    private var totalAttempts = 0
    private var windowStart = System.nanoTime()

    fun recordAttempt(phone: String?): Int? {
        val now = System.nanoTime()
        if (now - windowStart >= WINDOW_NANOS) {
            windowStart = now
            totalAttempts = 0
            attemptsByPhone.clear()
        }
        val key = phone?.let {
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)))
        }.orEmpty()
        val phoneAttempts = attemptsByPhone.getOrDefault(key, 0)
        if (totalAttempts >= processLimit || phoneAttempts >= phoneLimit) {
            val remaining = WINDOW_NANOS - (now - windowStart)
            return ((remaining + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).toInt()
        }
        totalAttempts++
        attemptsByPhone[key] = phoneAttempts + 1
        return null
    }
}
