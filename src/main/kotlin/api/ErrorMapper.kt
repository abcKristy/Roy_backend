package com.example.api

/** Safe transport description; step 8 installs it in Ktor StatusPages. */
data class ApiFailure(val status: Int, val code: String, val message: String)

class ErrorMapper {
    fun map(cause: Throwable): ApiFailure = ApiFailure(
        500,
        "INTERNAL_ERROR",
        "Внутренняя ошибка сервера",
    )
}
