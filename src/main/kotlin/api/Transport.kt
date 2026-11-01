package com.example.api

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal val transportJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
}

fun Application.configureTransport(errorMapper: ErrorMapper = ErrorMapper()) {
    val serverLog = environment.log
    install(RequestId)
    install(ContentNegotiation) {
        json(transportJson)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val failure = errorMapper.map(cause)
            val requestId = call.requestId()
            if (failure.status.value >= 500) {
                serverLog.error(
                    "requestId={} code={} exception={}",
                    requestId,
                    failure.error.code,
                    cause.javaClass.name,
                )
            }
            call.respond(failure.status, ErrorResponse(failure.error, requestId))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(ApiError("RESOURCE_GONE", "Ресурс не найден"), call.requestId()),
            )
        }
    }
}
