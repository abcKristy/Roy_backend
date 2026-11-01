package com.example.api

import com.example.auth.RegisterRequest
import com.example.auth.RegistrationService
import com.example.auth.LoginRequest
import com.example.auth.LoginService
import com.example.persistence.DatabaseRuntime
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(val status: String)

fun Application.configureRouting(
    database: DatabaseRuntime,
    registrationService: RegistrationService? = null,
    loginService: LoginService? = null,
) {
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
        if (registrationService != null) {
            post("/api/v1/auth/register") {
                val request = call.receiveStrictJson(RegisterRequest.serializer())
                call.respond(HttpStatusCode.Created, registrationService.register(request))
            }
        }
        if (loginService != null) {
            post("/api/v1/auth/login") {
                val request = call.receiveStrictJson(LoginRequest.serializer())
                call.respond(HttpStatusCode.OK, loginService.login(request))
            }
        }
    }
}
