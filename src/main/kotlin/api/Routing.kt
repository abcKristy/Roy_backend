package com.example.api

import com.example.auth.RegisterRequest
import com.example.auth.RegistrationService
import com.example.auth.LoginRequest
import com.example.auth.LoginService
import com.example.auth.SessionAccessService
import com.example.auth.DraftPrivacyPolicy
import com.example.auth.RefreshRequest
import com.example.auth.RefreshService
import com.example.persistence.DatabaseRuntime
import com.example.points.PointReadRepository
import com.example.points.PointWriteService
import com.example.moderation.ModerationService
import com.example.chats.ChatReadService
import com.example.chats.ChatSendService
import com.example.meetings.ProposalService
import com.example.meetings.BookingService
import com.example.notifications.NotificationService
import com.example.devices.DeviceService
import com.example.auth.ProfileService
import com.example.accounts.AccountDeletionService
import com.example.realtime.RealtimeHub
import com.example.realtime.realtimeRoutes
import com.example.geocoding.ReverseGeocoder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(val status: String)

@Serializable
private data class PrivacyMetadata(val version: String, val publishedAt: String, val url: String)

fun Application.configureRouting(
    database: DatabaseRuntime,
    registrationService: RegistrationService? = null,
    loginService: LoginService? = null,
    refreshService: RefreshService? = null,
    sessionAccess: SessionAccessService? = null,
    draftPrivacyPolicy: DraftPrivacyPolicy? = null,
    pointReadRepository: PointReadRepository? = null,
    pointWriteService: PointWriteService? = null,
    moderationService: ModerationService? = null,
    chatReadService: ChatReadService? = null,
    chatSendService: ChatSendService? = null,
    proposalService: ProposalService? = null,
    bookingService: BookingService? = null,
    notificationService: NotificationService? = null,
    deviceService: DeviceService? = null,
    profileService: ProfileService? = null,
    accountDeletionService: AccountDeletionService? = null,
    realtimeHub: RealtimeHub? = null,
    reverseGeocoder: ReverseGeocoder? = null,
    documentationBaseUrl: String? = null,
) {
    routing {
        documentationRoutes(documentationBaseUrl)
        if (realtimeHub != null) realtimeRoutes(realtimeHub)
        if (pointReadRepository != null) {
            pointReadRoutes(pointReadRepository, sessionAccess)
        }
        if (pointWriteService != null && sessionAccess != null) {
            pointWriteRoutes(pointWriteService, sessionAccess, realtimeHub)
        }
        if (reverseGeocoder != null && sessionAccess != null) {
            geocodingRoutes(reverseGeocoder, sessionAccess)
        }
        if (moderationService != null && sessionAccess != null) {
            reportRoutes(moderationService, sessionAccess)
        }
        if (chatReadService != null && sessionAccess != null) {
            chatReadRoutes(chatReadService, sessionAccess)
        }
        if (chatSendService != null && sessionAccess != null) {
            chatWriteRoutes(chatSendService, sessionAccess, realtimeHub)
        }
        if (proposalService != null && bookingService != null && sessionAccess != null) {
            meetingRoutes(proposalService, bookingService, sessionAccess, realtimeHub)
        }
        if (notificationService != null && sessionAccess != null) {
            notificationRoutes(notificationService, sessionAccess)
        }
        if (sessionAccess != null) {
            deviceRoutes(deviceService, sessionAccess)
        }
        if (profileService != null && sessionAccess != null) {
            profileRoutes(profileService, sessionAccess, realtimeHub)
        }
        if (accountDeletionService != null && sessionAccess != null) {
            accountDeletionRoutes(accountDeletionService, sessionAccess, realtimeHub)
        }
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/health") {
            if (!database.isReady()) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Сервис временно недоступен")
            }
            call.respond(HealthResponse("ok"))
        }
        get("/api/v1/legal/privacy") {
            val policy = draftPrivacyPolicy ?: throw ApiException(
                HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Политика пока не опубликована"
            )
            call.respond(PrivacyMetadata(policy.version, policy.publishedAt.toString(), policy.url))
        }
        get(DraftPrivacyPolicy.DOCUMENT_PATH) {
            val policy = draftPrivacyPolicy ?: throw ApiException(
                HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "Политика пока не опубликована"
            )
            call.respondText(policy.document, ContentType.parse("text/markdown; charset=UTF-8"))
        }
        if (registrationService != null) {
            post("/api/v1/auth/register") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val request = call.receiveStrictJson(RegisterRequest.serializer())
                call.respond(HttpStatusCode.Created, registrationService.register(request))
            }
        }
        if (loginService != null) {
            post("/api/v1/auth/login") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val request = call.receiveStrictJson(LoginRequest.serializer())
                call.respond(HttpStatusCode.OK, loginService.login(request))
            }
        }
        if (refreshService != null) {
            post("/api/v1/auth/refresh") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val request = call.receiveStrictJson(RefreshRequest.serializer())
                call.respond(HttpStatusCode.OK, refreshService.refresh(request))
            }
        }
        if (sessionAccess != null) {
            get("/api/v1/me") {
                call.respond(sessionAccess.currentUser(call.request.headers[HttpHeaders.Authorization]))
            }
            post("/api/v1/auth/logout") {
                sessionAccess.logout(call.request.headers[HttpHeaders.Authorization])
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
