package com.example.api

import com.example.accounts.AccountDeletionResult
import com.example.accounts.AccountDeletionService
import com.example.auth.SessionAccessService
import com.example.realtime.RealtimeHub
import com.example.realtime.RealtimeEvent
import com.example.realtime.RealtimeEventType
import com.example.realtime.RealtimeScopeKind
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

@Serializable
private data class AccountDeletionInput(val currentPassword: String, val confirm: Boolean)

internal fun Route.accountDeletionRoutes(service: AccountDeletionService, sessions: SessionAccessService,
                                         realtime: RealtimeHub? = null) {
    post("/api/v1/me/deletion") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val identity = sessions.currentIdentity(call.request.headers[HttpHeaders.Authorization])
        val input = call.receiveStrictJson(AccountDeletionInput.serializer())
        if (!input.confirm || input.currentPassword.isBlank() || input.currentPassword.length > 1024) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Подтвердите удаление и укажите пароль")
        }
        when (val result = service.delete(identity, input.currentPassword)) {
            is AccountDeletionResult.Deleted -> {
                if (realtime != null) {
                    for (emission in result.membershipMessages) {
                        val message = emission.asMessageView()
                        try { realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.CHAT_MESSAGE_CREATED,
                            RealtimeScopeKind.CHAT, message.chatId, message.createdAt, messageJson(message))) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { call.application.log.warn("Membership broadcast failed", failure) }
                    }
                    for (change in result.meetingChanges) {
                        val proposal = change.proposal
                        for (emission in change.messages) {
                            val message = emission.asMessageView()
                            try { realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.CHAT_MESSAGE_CREATED,
                                RealtimeScopeKind.CHAT, proposal.chatId, message.createdAt, messageJson(message))) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { call.application.log.warn("Meeting message broadcast failed", failure) }
                        }
                        try {
                            realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.PROPOSAL_UPDATED,
                                RealtimeScopeKind.CHAT, proposal.chatId, proposal.updatedAt, buildJsonObject {
                                    put("proposalId", proposal.id.toString()); put("revision", proposal.revision)
                                    put("status", proposal.status); put("goingCount", proposal.goingCount)
                                    put("bookingId", proposal.booking?.id?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                }))
                            change.bookingChange?.let { booking ->
                                realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.BOOKING_CHANGED,
                                    RealtimeScopeKind.CHAT, proposal.chatId, Instant.now(), buildJsonObject {
                                        put("proposalId", proposal.id.toString()); put("bookingId", booking.bookingId.toString())
                                        put("status", booking.status); put("cancelReason", booking.cancelReason)
                                    }))
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { call.application.log.warn("Meeting state broadcast failed", failure) }
                    }
                }
                if (realtime != null) for (chatId in result.membershipMessages.map { it.message.chatId }.distinct()) {
                    try { realtime.publishPointChangedForChat(chatId) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { call.application.log.warn("Point change broadcast failed", failure) }
                }
                if (realtime != null) for (point in result.removedPoints) for (recipientId in point.formerMemberIds) {
                    try {
                        realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.POINT_DELETED,
                            RealtimeScopeKind.USER, recipientId, Instant.now(),
                            buildJsonObject { put("pointId", point.pointId.toString()); put("reason", "no_members") }))
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { call.application.log.warn("Point deletion broadcast failed", failure) }
                }
                if (realtime != null) for (sessionId in result.revokedSessionIds) {
                    try { realtime.closeRevokedSession(sessionId, "expired") }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { call.application.log.warn("Session close after account deletion failed", failure) }
                }
                call.respond(HttpStatusCode.NoContent)
            }
            AccountDeletionResult.WrongPassword -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, "INVALID_CURRENT_PASSWORD", "Неверный текущий пароль")
            AccountDeletionResult.SessionExpired -> throw ApiException(
                HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "Сеанс истёк. Войдите снова")
        }
    }
}
