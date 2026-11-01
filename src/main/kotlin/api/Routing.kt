package com.example.api

import com.example.persistence.DatabaseRuntime
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(val status: String)

fun Application.configureRouting(database: DatabaseRuntime) {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/health") {
            if (!database.isReady()) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Сервис временно недоступен")
            }
            call.respond(HealthResponse("ok"))
        }
    }
}
