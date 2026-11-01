package com.example

import com.example.api.configureRouting
import com.example.config.ServerConfig
import com.example.persistence.FlywayMigrationRunner
import io.ktor.server.application.Application

/** Ktor composition root. Migrations must succeed before routes are installed. */
fun Application.module() {
    val config = ServerConfig.fromEnvironment()
    FlywayMigrationRunner(config).migrate()
    configureRouting()
}
