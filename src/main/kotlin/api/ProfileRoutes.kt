package com.example.api

import com.example.auth.ChangePasswordRequest
import com.example.auth.ProfileService
import com.example.auth.SessionAccessService
import com.example.auth.UpdateProfileRequest
import com.example.auth.avatarNotFound
import com.example.realtime.RealtimeHub
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

private val log = LoggerFactory.getLogger("ProfileRoutes")
private val avatarValidatorMember = Regex("""[ \t]*(?:(?:W/)?("[!#-~\x80-\xFF]*"))?[ \t]*(?:,|\z)""")

fun Route.profileRoutes(
    profile: ProfileService,
    sessions: SessionAccessService,
    realtime: RealtimeHub?,
    avatarBodyTimeoutMillis: Long = AVATAR_BODY_TIMEOUT_MILLIS,
) {
    get("/api/v1/users/{userId}/avatar") {
        call.response.header(HttpHeaders.Vary, HttpHeaders.Authorization)
        val image = try {
            val identity = sessions.currentIdentity(call.request.headers[HttpHeaders.Authorization])
            val rawId = call.parameters["userId"] ?: avatarNotFound()
            val targetId = try {
                UUID.fromString(rawId)
            } catch (_: IllegalArgumentException) {
                avatarNotFound()
            }
            if (!targetId.toString().equals(rawId, ignoreCase = true)) avatarNotFound()
            profile.readAvatar(identity, targetId)
        } catch (failure: ApiException) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            throw failure
        }
        val mediaType = image.contentType.mimeType
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(mediaType.encodeToByteArray())
        val tag = "\"${HexFormat.of().formatHex(digest.digest(image.bytes))}\""
        call.response.header(HttpHeaders.ETag, tag)
        call.response.header(HttpHeaders.CacheControl, "private, no-cache")
        call.response.header("X-Content-Type-Options", "nosniff")

        val validator = call.request.headers.getAll(HttpHeaders.IfNoneMatch)?.joinToString(",")
        if (validator != null && avatarTagMatches(validator, tag)) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respondBytes(image.bytes, ContentType.parse(mediaType))
        }
    }

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
                log.warn("Post-commit password-change socket close failed exception={}", failure.javaClass.name)
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun avatarTagMatches(validator: String, tag: String): Boolean {
    if (validator.trim(' ', '\t') == "*") return true
    var offset = 0
    var matches = false
    while (offset < validator.length) {
        // Parse a complete member so a comma inside the opaque tag stays part of it.
        val member = avatarValidatorMember.matchAt(validator, offset) ?: return false
        if (member.groupValues[1] == tag) matches = true
        offset = member.range.last + 1
    }
    return matches
}
