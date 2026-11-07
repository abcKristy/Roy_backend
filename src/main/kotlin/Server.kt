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
import com.example.auth.ProfileService
import com.example.accounts.AccountDeletionService
import com.example.auth.SessionAccessService
import com.example.auth.RefreshService
import com.example.config.AppEnvironment
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
import com.example.meetings.ProposalService
import com.example.meetings.BookingService
import com.example.notifications.NotificationService
import com.example.devices.DeviceService
import com.example.devices.DeviceTokenCipher
import com.example.realtime.RealtimeHub
import com.example.realtime.RealtimeEvent
import com.example.realtime.RealtimeEventType
import com.example.realtime.RealtimeScopeKind
import com.example.jobs.JobRuntime
import com.example.jobs.ReminderScheduler
import com.example.jobs.ProposalExpiryScheduler
import com.example.jobs.NotificationBroadcaster
import com.example.jobs.RetentionCleanup
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import com.example.realtime.installRealtimeTransport
import com.example.geocoding.DaDataReverseGeocoder
import com.example.storage.LocalAvatarStore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import java.nio.file.Path

/** Ktor composition root. Migrations must succeed before routes are installed. */
fun Application.module() {
    val config = ServerConfig.fromEnvironment()
    val database = HikariDatabaseBootstrap(config).start()
    try {
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
        val jobs = JobRuntime(
            reminders = ReminderScheduler(database),
            expiry = ProposalExpiryScheduler(database),
            broadcaster = NotificationBroadcaster(database, publish = { recipientId, eventId, payload, createdAt ->
                realtimeHub.publishCommitted(RealtimeEvent(
                    eventId, RealtimeEventType.NOTIFICATION_CREATED, RealtimeScopeKind.USER,
                    recipientId, createdAt, payload,
                ))
            }),
            cleanup = RetentionCleanup(database),
            onExpired = { expired ->
                expired.forEach { item ->
                    val messagePayload = buildJsonObject {
                        put("id", item.message.id.toString())
                        put("chatId", item.chatId.toString())
                        put("messageSeq", item.message.messageSeq)
                        put("type", "system")
                        put("author", JsonNull)
                        put("text", JsonNull)
                        put("system", buildJsonObject {
                            put("kind", "meeting_closed")
                            put("actorUserId", JsonNull)
                            put("pointId", item.draft.pointId.toString())
                            put("chatId", item.chatId.toString())
                            put("proposalId", item.proposalId.toString())
                            put("bookingId", JsonNull)
                            put("startsAt", JsonNull)
                            put("endsAt", JsonNull)
                            put("reason", JsonNull)
                        })
                        put("createdAt", item.message.createdAt.toString())
                    }
                    realtimeHub.publishCommitted(RealtimeEvent(UUID.randomUUID(),
                        RealtimeEventType.CHAT_MESSAGE_CREATED, RealtimeScopeKind.CHAT,
                        item.chatId, item.message.createdAt, messagePayload))
                    realtimeHub.publishCommitted(RealtimeEvent(UUID.randomUUID(),
                        RealtimeEventType.PROPOSAL_UPDATED, RealtimeScopeKind.CHAT,
                        item.chatId, item.updatedAt, buildJsonObject {
                            put("proposalId", item.proposalId.toString())
                            put("revision", item.revision)
                            put("status", "closed")
                            put("goingCount", item.goingCount)
                            put("bookingId", JsonNull)
                        }))
                }
            },
        )
        configureRouting(
            documentationBaseUrl = config.publicBaseUrl?.toString(),
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
            reverseGeocoder = DaDataReverseGeocoder(config.dadataApiKey),
            moderationService = ModerationService(database),
            chatReadService = ChatReadService(database, config.jwtSecret),
            chatSendService = ChatSendService(database, MessageContentPolicy()),
            proposalService = ProposalService(database, config.jwtSecret),
            bookingService = BookingService(database),
            notificationService = NotificationService(
                database, config.jwtSecret, locationPrivacyVersion = draftPolicy?.version,
            ),
            deviceService = config.pushTokenEncryptionKeyBase64?.let { key ->
                DeviceService(database, DeviceTokenCipher.fromBase64(key))
            },
            profileService = ProfileService(
                database, passwordHashing,
                avatarStore = if (config.appEnvironment == AppEnvironment.LOCAL) {
                    LocalAvatarStore(Path.of(".local", "avatars"))
                } else null,
            ),
            accountDeletionService = AccountDeletionService(database, passwordHashing),
            realtimeHub = realtimeHub,
        )
        jobs.start()
        monitor.subscribe(ApplicationStopped) {
            jobs.close()
            database.close()
        }
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}
