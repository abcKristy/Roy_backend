package com.example

import com.example.api.configureRouting
import com.example.api.configureTransport
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.JwtTokenIssuer
import com.example.auth.LoginService
import com.example.auth.RegistrationService
import com.example.auth.UnpublishedPrivacyPolicy
import com.example.auth.DraftPrivacyPolicy
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionAccessService
import com.example.auth.RefreshService
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.persistence.JdbcRegistrationRepository
import com.example.persistence.JdbcLoginRepository
import com.example.persistence.JdbcSessionRepository
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
        val draftPolicy = if (config.draftPrivacyEnabled) DraftPrivacyPolicy(requireNotNull(config.publicBaseUrl)) else null
        configureRouting(
            database = database,
            registrationService = RegistrationService(
                policy = draftPolicy ?: UnpublishedPrivacyPolicy,
                passwordHashing = passwordHashing,
                repository = JdbcRegistrationRepository(database),
                tokenIssuer = tokenIssuer,
                accessTtl = config.jwtTtl,
                refreshTtl = config.refreshTtl,
            ),
            loginService = LoginService(
                JdbcLoginRepository(database), passwordHashing, tokenIssuer,
                accessTtl = config.jwtTtl, refreshTtl = config.refreshTtl,
            ),
            refreshService = RefreshService(JdbcSessionRepository(database), tokenIssuer, accessTtl = config.jwtTtl),
            sessionAccess = SessionAccessService(config.jwtSecret, JdbcSessionRepository(database)),
            draftPrivacyPolicy = draftPolicy,
        )
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}
