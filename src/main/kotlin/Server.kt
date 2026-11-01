package com.example

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.LoginService
import com.example.auth.RegistrationService
import com.example.auth.UnpublishedPrivacyPolicy
import com.example.auth.PasswordHashingWork
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcRegistrationRepository
import com.example.persistence.JdbcLoginRepository
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped

/** Ktor composition root. Migrations must succeed before routes are installed. */
fun Application.module() {
    val config = ServerConfig.fromEnvironment()
    val database = HikariDatabaseBootstrap(config).start()
    try {
        monitor.subscribe(ApplicationStopped) { database.close() }
        configureTransport()
        val passwordHashing = PasswordHashingWork(Argon2idPasswordHasher())
        val tokenIssuer = JwtTokenIssuer(config.jwtSecret)
        configureRouting(
            database,
            RegistrationService(
                policy = UnpublishedPrivacyPolicy,
                passwordHashing = passwordHashing,
                repository = JdbcRegistrationRepository(database),
                tokenIssuer = tokenIssuer,
                sessionTtl = config.jwtTtl,
            ),
            LoginService(JdbcLoginRepository(database), passwordHashing, tokenIssuer, sessionTtl = config.jwtTtl),
        )
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}
