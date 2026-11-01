package com.example

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped

/** Ktor composition root. Migrations must succeed before routes are installed. */
fun Application.module() {
    val config = ServerConfig.fromEnvironment()
    val database = HikariDatabaseBootstrap(config).start()
    try {
        monitor.subscribe(ApplicationStopped) { database.close() }
        configureTransport()
        configureRouting()
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}
