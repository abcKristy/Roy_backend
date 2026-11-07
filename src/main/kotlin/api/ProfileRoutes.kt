package com.example.api

import com.example.auth.ChangePasswordRequest
import com.example.auth.ProfileService
import com.example.auth.SessionAccessService
import com.example.auth.UpdateProfileRequest
import com.example.realtime.RealtimeHub
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ProfileRoutes")

fun Route.profileRoutes(
    profile: ProfileService,
    sessions: SessionAccessService,
    realtime: RealtimeHub?,
    avatarBodyTimeoutMillis: Long = AVATAR_BODY_TIMEOUT_MILLIS,
) {
    // Limit admission before reading the bounded multipart body or decoding its raster.
    val avatarUploads = Semaphore(2)
    put("/api/v1/me/avatar") {
        val identity = sessions.currentIdentity(call.request.headers[HttpHeaders.Authorization])
        val current = profile.captureAvatarState(identity)
        val result = avatarUploads.withPermit {
            profile.replaceAvatar(identity, current, call.receiveAvatarImage(avatarBodyTimeoutMillis))
        }
        call.respond(result)
    }
    delete("/api/v1/me/avatar") {
        val identity = sessions.currentIdentity(call.request.headers[HttpHeaders.Authorization])
        profile.removeAvatar(identity)
        call.respond(HttpStatusCode.NoContent)
    }
    patch("/api/v1/me") {
        val identity = sessions.currentIdentity(call.request.headers[HttpHeaders.Authorization])
        val request = call.receiveStrictJson(UpdateProfileRequest.serializer())
        call.respond(profile.updateName(identity, request))
    }
    post("/api/v1/me/password") {
        val identity = sessions.currentIdentity(call.request.headers[HttpHeaders.Authorization])
        val request = call.receiveStrictJson(ChangePasswordRequest.serializer())
        val revoked = profile.changePassword(identity, request)
        if (realtime != null) for (sessionId in revoked) {
            try {
                realtime.closeRevokedSession(sessionId, "password_changed")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                log.warn("Post-commit password-change socket close failed", failure)
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
