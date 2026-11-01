package com.example

import com.example.api.configureRouting
import io.ktor.server.application.Application

/** Ktor composition root. Database startup and transport plugins are wired in later steps. */
fun Application.module() {
    configureRouting()
}
