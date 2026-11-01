package com.example

import com.example.api.configureRouting
import com.example.config.ServerConfig
import io.ktor.server.application.Application

/** Ktor composition root. Database startup and transport plugins are wired in later steps. */
fun Application.module() {
    ServerConfig.fromEnvironment()
    configureRouting()
}
