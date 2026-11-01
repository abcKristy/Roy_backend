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
import com.example.points.PointReadRepository
import com.example.points.PointWriteService
import com.example.moderation.ModerationService
import com.example.chats.ChatReadService
import com.example.chats.ChatSendService
import com.example.chats.MessageContentPolicy
import com.example.realtime.RealtimeHub
import com.example.realtime.installRealtimeTransport
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped

/** Ktor composition root. Migrations must succeed before routes are installed. */
fun Application.module() {
    val config = ServerConfig.fromEnvironment()
    val database = HikariDatabaseBootstrap(config).start()
    try {
        monitor.subscribe(ApplicationStopped) { database.close() }
        configureTransport()
        installRealtimeTransport()
        val passwordHashing = PasswordHashingWork(Argon2idPasswordHasher())
        val tokenIssuer = JwtTokenIssuer(config.jwtSecret)
        val draftPolicy = if (config.draftPrivacyEnabled) DraftPrivacyPolicy(requireNotNull(config.publicBaseUrl)) else null
        val sessionRepository = JdbcSessionRepository(database)
        lateinit var realtimeHub: RealtimeHub
        val sessionAccess = SessionAccessService(config.jwtSecret, sessionRepository,
            onSessionRevoked = { identity -> realtimeHub.closeRevokedSession(identity.sessionId) })
        realtimeHub = RealtimeHub(database, sessionAccess)
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
            refreshService = RefreshService(sessionRepository, tokenIssuer, accessTtl = config.jwtTtl),
            sessionAccess = sessionAccess,
            draftPrivacyPolicy = draftPolicy,
            pointReadRepository = PointReadRepository(database, config.jwtSecret),
            pointWriteService = PointWriteService(database),
            moderationService = ModerationService(database),
            chatReadService = ChatReadService(database, config.jwtSecret),
            chatSendService = ChatSendService(database, MessageContentPolicy()),
            realtimeHub = realtimeHub,
        )
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}
