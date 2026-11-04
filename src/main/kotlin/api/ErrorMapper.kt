package com.example.api

import com.example.persistence.InactiveActorException
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
    val retryAfterSeconds: Int? = null,
)

@Serializable
data class ErrorResponse(val error: ApiError, val requestId: String)

data class ApiFailure(val status: HttpStatusCode, val error: ApiError)

/** Only explicit transport failures can become 4xx. Other exceptions are hidden from clients. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    val safeMessage: String,
    val details: JsonObject? = null,
    val retryAfterSeconds: Int? = null,
) : RuntimeException(code)

class ErrorMapper {
    fun map(cause: Throwable): ApiFailure = when (cause) {
        is ApiException -> ApiFailure(
            cause.status,
            ApiError(cause.code, cause.safeMessage, cause.details, cause.retryAfterSeconds),
        )
        is InactiveActorException -> ApiFailure(
            HttpStatusCode.Unauthorized,
            ApiError("SESSION_EXPIRED", "Сеанс истёк. Войдите снова"),
        )
        is BadRequestException -> ApiFailure(
            HttpStatusCode.BadRequest,
            ApiError("INVALID_JSON", "Некорректный JSON"),
        )
        else -> ApiFailure(
            HttpStatusCode.InternalServerError,
            ApiError("INTERNAL_ERROR", "Внутренняя ошибка сервера"),
        )
    }
}
