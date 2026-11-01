package com.example

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.RegistrationService
import com.example.auth.UnpublishedPrivacyPolicy
import com.example.auth.PasswordHashingWork
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcRegistrationRepository
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped

/** Ktor composition root. Migrations must succeed before routes are installed. */
fun Application.module() {
    val config = ServerConfig.fromEnvironment()
    val database = HikariDatabaseBootstrap(config).start()
    try {
        monitor.subscribe(ApplicationStopped) { database.close() }
        configureTransport()
        configureRouting(
            database,
            RegistrationService(
                policy = UnpublishedPrivacyPolicy,
                passwordHashing = PasswordHashingWork(Argon2idPasswordHasher()),
                repository = JdbcRegistrationRepository(database),
                tokenIssuer = JwtTokenIssuer(config.jwtSecret),
            ),
        )
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}
