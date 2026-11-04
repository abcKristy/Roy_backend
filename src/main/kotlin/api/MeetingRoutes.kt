package com.example.api

import com.example.auth.SessionAccessService
import com.example.meetings.BookMeetingCommand
import com.example.meetings.BookMeetingResult
import com.example.meetings.BookingChange
import com.example.meetings.BookingConflictView
import com.example.meetings.BookingService
import com.example.meetings.BookingView
import com.example.meetings.CloseMeetingCommand
import com.example.meetings.CloseMeetingResult
import com.example.meetings.CreateProposalCommand
import com.example.meetings.CreateProposalResult
import com.example.meetings.MeetingEmission
import com.example.meetings.ProposalService
import com.example.meetings.ProposalView
import com.example.meetings.VoteCommand
import com.example.meetings.VoteResult
import com.example.meetings.VoteView
import com.example.realtime.RealtimeEvent
import com.example.realtime.RealtimeEventType
import com.example.realtime.RealtimeHub
import com.example.realtime.RealtimeScopeKind
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

@Serializable private data class ProposalInput(val startsAt: String, val endsAt: String)
@Serializable private data class VoteInput(
    @Serializable(with = StrictVoteBooleanSerializer::class) val going: Boolean,
)

/** JSON Boolean only: the standard decoder also accepts quoted boolean text. */
private object StrictVoteBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("StrictVoteBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected JSON Boolean")
        if (value.isString) throw SerializationException("Expected JSON Boolean")
        return value.booleanOrNull ?: throw SerializationException("Expected JSON Boolean")
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}
@Serializable private data class BookingInput(
    @Serializable(with = StrictVoteBooleanSerializer::class) val confirmOverlap: Boolean,
    @Serializable(with = StrictBookingRevisionSerializer::class) val expectedRevision: Long,
)

/** Booking revisions are native JSON integers, never quoted numeric text. */
private object StrictBookingRevisionSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("StrictBookingRevision", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected JSON Long")
        if (value.isString) throw SerializationException("Expected JSON Long")
        return value.longOrNull ?: throw SerializationException("Expected JSON Long")
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}
@Serializable private data class CloseProposalInput(
    @Serializable(with = StrictBookingRevisionSerializer::class) val expectedRevision: Long,
)

internal fun Route.meetingRoutes(
    proposals: ProposalService,
    bookings: BookingService,
    sessions: SessionAccessService,
    realtime: RealtimeHub? = null,
) {
    get("/api/v1/chats/{chatId}/proposals") {
        val actor = meetingActor(sessions, call)
        val chatId = meetingId(call.parameters["chatId"])
        val page = try {
            proposals.list(chatId, actor, call.request.queryParameters["cursor"], meetingLimit(call.request.queryParameters["limit"]))
        } catch (_: IllegalArgumentException) {
            throw invalidMeetingCursor()
        } ?: throw noMeetingAccess()
        call.respond(buildJsonObject {
            put("items", JsonArray(page.items.map(::proposalJson)))
            put("nextCursor", page.nextCursor?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
    get("/api/v1/proposals/{proposalId}") {
        val actor = meetingActor(sessions, call)
        val proposal = proposals.detail(meetingId(call.parameters["proposalId"]), actor) ?: throw noMeetingAccess()
        call.respond(proposalJson(proposal))
    }
    get("/api/v1/proposals/{proposalId}/votes") {
        val actor = meetingActor(sessions, call)
        val proposalId = meetingId(call.parameters["proposalId"])
        val page = try {
            proposals.votes(proposalId, actor, call.request.queryParameters["cursor"], meetingLimit(call.request.queryParameters["limit"]))
        } catch (_: IllegalArgumentException) {
            throw invalidMeetingCursor()
        } ?: throw noMeetingAccess()
        call.respond(buildJsonObject {
            put("items", JsonArray(page.items.map(::voteJson)))
            put("nextCursor", page.nextCursor?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
    post("/api/v1/chats/{chatId}/proposals") {
        val actor = meetingActor(sessions, call)
        val chatId = meetingId(call.parameters["chatId"])
        val key = meetingId(call.request.headers["Idempotency-Key"])
        val input = call.receiveStrictJson(ProposalInput.serializer())
        val startsAt = meetingInstant(input.startsAt)
        val endsAt = meetingInstant(input.endsAt)
        when (val result = proposals.create(CreateProposalCommand(actor, chatId, key, startsAt, endsAt)) { _, value ->
            proposalJson(value)
        }) {
            is CreateProposalResult.Created -> {
                call.publishMeeting(realtime, result.proposal, result.emissions.map(MeetingEmission::asMessageView))
                call.respondMeetingSnapshot(result.responseSnapshot)
            }
            is CreateProposalResult.Replay -> call.respondMeetingSnapshot(result.responseSnapshot)
            CreateProposalResult.KeyReused -> throw meetingConflict("IDEMPOTENCY_KEY_REUSED", "Ключ запроса уже использован")
            CreateProposalResult.ChatGone -> throw meetingGone()
            CreateProposalResult.NotMember -> throw noMeetingAccess()
            CreateProposalResult.InvalidInterval -> throw invalidMeetingInterval()
        }
    }
    put("/api/v1/proposals/{proposalId}/vote") {
        val actor = meetingActor(sessions, call)
        val proposalId = meetingId(call.parameters["proposalId"])
        val input = call.receiveStrictJson(VoteInput.serializer())
        when (val result = proposals.vote(VoteCommand(actor, proposalId, input.going))) {
            is VoteResult.Updated -> {
                call.publishMeeting(realtime, result.proposal, result.emissions.map(MeetingEmission::asMessageView),
                    result.bookingChange)
                call.respond(proposalJson(result.proposal))
            }
            is VoteResult.Unchanged -> call.respond(proposalJson(result.proposal))
            VoteResult.ProposalGone -> throw meetingGone()
            VoteResult.NotMember -> throw noMeetingAccess()
            VoteResult.Closed -> throw meetingConflict("PROPOSAL_CLOSED", "Предложение закрыто")
            VoteResult.Started -> throw meetingConflict("MEETING_ALREADY_STARTED", "Встреча уже началась")
        }
    }
    post("/api/v1/proposals/{proposalId}/booking") {
        val actor = meetingActor(sessions, call)
        val proposalId = meetingId(call.parameters["proposalId"])
        val key = meetingId(call.request.headers["Idempotency-Key"])
        val input = call.receiveStrictJson(BookingInput.serializer())
        if (input.expectedRevision < 0) throw invalidMeetingField()
        when (val result = bookings.book(BookMeetingCommand(actor, proposalId, key,
            input.confirmOverlap, input.expectedRevision)) { _, value -> proposalJson(value) }) {
            is BookMeetingResult.Created -> {
                call.publishMeeting(realtime, result.proposal, result.systemMessages, result.bookingChange)
                call.respondMeetingSnapshot(result.responseSnapshot)
            }
            is BookMeetingResult.Replay -> call.respondMeetingSnapshot(result.responseSnapshot)
            is BookMeetingResult.OverlapConfirmRequired -> throw ApiException(
                HttpStatusCode.Conflict, "OVERLAP_CONFIRM_REQUIRED", "Время пересекается с другой встречей",
                buildJsonObject { put("overlaps", JsonArray(result.overlaps.map(::bookingConflictJson))) })
            BookMeetingResult.KeyReused -> throw meetingConflict("IDEMPOTENCY_KEY_REUSED", "Ключ запроса уже использован")
            BookMeetingResult.Gone -> throw meetingGone()
            BookMeetingResult.NotMember -> throw noMeetingAccess()
            BookMeetingResult.Stale -> throw meetingConflict("STALE_STATE", "Состояние встречи изменилось")
            BookMeetingResult.AlreadyStarted -> throw meetingConflict("MEETING_ALREADY_STARTED", "Встреча уже началась")
            BookMeetingResult.VoteRequired -> throw meetingConflict("VOTE_REQUIRED", "Сначала подтвердите участие")
            BookMeetingResult.InsufficientVotes -> throw meetingConflict("INSUFFICIENT_VOTES", "Недостаточно участников")
            BookMeetingResult.ProposalClosed -> throw meetingConflict("PROPOSAL_CLOSED", "Предложение закрыто")
            BookMeetingResult.AlreadyBooked -> throw meetingConflict("ALREADY_BOOKED", "Встреча уже забронирована")
        }
    }
    post("/api/v1/proposals/{proposalId}/close") {
        val actor = meetingActor(sessions, call)
        val proposalId = meetingId(call.parameters["proposalId"])
        val key = meetingId(call.request.headers["Idempotency-Key"])
        val input = call.receiveStrictJson(CloseProposalInput.serializer())
        if (input.expectedRevision < 0) throw invalidMeetingField()
        when (val result = bookings.close(CloseMeetingCommand(actor, proposalId, key, input.expectedRevision)) { _, value ->
            proposalJson(value)
        }) {
            is CloseMeetingResult.Created -> {
                call.publishMeeting(realtime, result.proposal, result.systemMessages, result.bookingChange)
                call.respondMeetingSnapshot(result.responseSnapshot)
            }
            is CloseMeetingResult.Replay -> call.respondMeetingSnapshot(result.responseSnapshot)
            CloseMeetingResult.KeyReused -> throw meetingConflict("IDEMPOTENCY_KEY_REUSED", "Ключ запроса уже использован")
            CloseMeetingResult.Gone -> throw meetingGone()
            CloseMeetingResult.NotMember -> throw noMeetingAccess()
            CloseMeetingResult.Stale -> throw meetingConflict("STALE_STATE", "Состояние встречи изменилось")
            CloseMeetingResult.AlreadyStarted -> throw meetingConflict("MEETING_ALREADY_STARTED", "Встреча уже началась")
            CloseMeetingResult.NotAuthor -> throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Закрыть предложение может только автор")
            CloseMeetingResult.AlreadyClosed -> throw meetingConflict("PROPOSAL_CLOSED", "Предложение закрыто")
        }
    }
}

internal fun proposalJson(value: ProposalView): JsonObject = buildJsonObject {
    put("id", value.id.toString())
    put("chatId", value.chatId.toString())
    put("proposedByUserId", value.proposedByUserId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    put("startsAt", value.startsAt.toString())
    put("endsAt", value.endsAt.toString())
    put("status", value.status)
    put("goingCount", value.goingCount)
    put("viewerGoing", value.viewerGoing)
    put("booking", value.booking?.let(::bookingJson) ?: JsonNull)
    put("revision", value.revision)
    put("createdAt", value.createdAt.toString())
    put("updatedAt", value.updatedAt.toString())
}

private fun bookingJson(value: BookingView): JsonObject = buildJsonObject {
    put("id", value.id.toString())
    put("proposalId", value.proposalId.toString())
    put("bookedByUserId", value.bookedByUserId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    put("status", value.status)
    put("bookingOrder", value.bookingOrder)
    put("overlappingActive", JsonArray(value.overlappingActive.map(::bookingConflictJson)))
    put("bookedAt", value.bookedAt.toString())
    put("cancelledAt", value.cancelledAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    put("cancelReason", value.cancelReason?.let(::JsonPrimitive) ?: JsonNull)
}

private fun bookingConflictJson(value: BookingConflictView): JsonObject = buildJsonObject {
    put("bookingId", value.bookingId.toString())
    put("proposalId", value.proposalId.toString())
    put("startsAt", value.startsAt.toString())
    put("endsAt", value.endsAt.toString())
    put("bookedAt", value.bookedAt.toString())
    put("bookingOrder", value.bookingOrder)
}

private fun voteJson(value: VoteView): JsonObject = buildJsonObject {
    put("proposalId", value.proposalId.toString())
    put("user", buildJsonObject {
        put("id", value.userId.toString())
        put("displayName", value.displayName)
        put("avatarUrl", if (value.avatarObjectKey != null) JsonPrimitive("/api/v1/users/${value.userId}/avatar") else JsonNull)
    })
    put("createdAt", value.createdAt.toString())
}

private suspend fun ApplicationCall.publishMeeting(
    realtime: RealtimeHub?, proposal: ProposalView, messages: List<com.example.chats.MessageView>,
    bookingChange: BookingChange? = null,
) {
    if (realtime == null) return
    try {
        withTimeoutOrNull(1_500) {
            messages.forEach { message ->
                realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.CHAT_MESSAGE_CREATED,
                    RealtimeScopeKind.CHAT, proposal.chatId, message.createdAt, messageJson(message)))
            }
            realtime.publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.PROPOSAL_UPDATED,
                RealtimeScopeKind.CHAT, proposal.chatId, proposal.updatedAt, buildJsonObject {
                    put("proposalId", proposal.id.toString())
                    put("revision", proposal.revision)
                    put("status", proposal.status)
                    put("goingCount", proposal.goingCount)
                    put("bookingId", proposal.booking?.id?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                }))
            if (bookingChange != null) realtime.publishCommitted(RealtimeEvent(
                UUID.randomUUID(), RealtimeEventType.BOOKING_CHANGED,
                RealtimeScopeKind.CHAT, proposal.chatId, proposal.updatedAt, buildJsonObject {
                    put("proposalId", proposal.id.toString())
                    put("bookingId", bookingChange.bookingId.toString())
                    put("status", bookingChange.status)
                    put("cancelReason", bookingChange.cancelReason?.let(::JsonPrimitive) ?: JsonNull)
                }))
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        application.log.warn("Post-commit meeting WebSocket delivery failed for proposal {}", proposal.id, failure)
    }
}

private suspend fun ApplicationCall.respondMeetingSnapshot(snapshot: JsonObject) {
    val status = snapshot["status"]?.jsonPrimitive?.int ?: error("Saved proposal has no HTTP status")
    val body = snapshot["body"] ?: error("Saved proposal has no HTTP body")
    respond(HttpStatusCode.fromValue(status), body)
}

private suspend fun meetingActor(sessions: SessionAccessService, call: ApplicationCall): UUID =
    UUID.fromString(sessions.currentUser(call.request.headers[HttpHeaders.Authorization]).id)

private fun meetingId(raw: String?): UUID = try {
    UUID.fromString(raw ?: throw IllegalArgumentException())
} catch (_: IllegalArgumentException) {
    throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный идентификатор")
}

private fun meetingInstant(raw: String): Instant = try {
    Instant.parse(raw)
} catch (_: DateTimeParseException) {
    throw invalidMeetingField()
}

private fun meetingLimit(raw: String?): Int =
    if (raw == null) 30 else raw.toIntOrNull()?.takeIf { it in 1..100 } ?: throw ApiException(
        HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный размер страницы")

private fun invalidMeetingCursor() = ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Некорректный курсор")
private fun invalidMeetingField() = ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректные данные встречи")
private fun invalidMeetingInterval() = ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректное время встречи")
private fun noMeetingAccess() = ApiException(HttpStatusCode.Forbidden, "NOT_MEMBER", "Нет доступа к встрече")
private fun meetingGone() = ApiException(HttpStatusCode.NotFound, "RESOURCE_GONE", "Предложение не найдено")
private fun meetingConflict(code: String, message: String) = ApiException(HttpStatusCode.Conflict, code, message)
