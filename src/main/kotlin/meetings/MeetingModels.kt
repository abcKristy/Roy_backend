package com.example.meetings

import com.example.chats.MessageView
import com.example.persistence.StoredSystemMessage
import com.example.persistence.SystemMessageDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

data class BookingConflictView(
    val bookingId: UUID, val proposalId: UUID, val startsAt: Instant,
    val endsAt: Instant, val bookedAt: Instant, val bookingOrder: Long,
)

data class BookingView(
    val id: UUID, val proposalId: UUID, val bookedByUserId: UUID?, val status: String,
    val bookingOrder: Long, val overlappingActive: List<BookingConflictView>,
    val bookedAt: Instant, val cancelledAt: Instant?, val cancelReason: String?,
)

data class ProposalView(
    val id: UUID, val chatId: UUID, val proposedByUserId: UUID?,
    val startsAt: Instant, val endsAt: Instant, val status: String,
    val goingCount: Int, val viewerGoing: Boolean, val booking: BookingView?,
    val revision: Long, val createdAt: Instant, val updatedAt: Instant,
)

data class VoteView(
    val proposalId: UUID, val userId: UUID, val displayName: String,
    val avatarObjectKey: String?, val createdAt: Instant,
)

data class PageView<T>(val items: List<T>, val nextCursor: String?)

data class BookingChange(val bookingId: UUID, val status: String, val cancelReason: String?)

/** A SYSTEM message committed with its business operation, ready for post-commit WS delivery. */
data class MeetingEmission(val message: StoredSystemMessage, val draft: SystemMessageDraft) {
    fun asMessageView(): MessageView = MessageView(
        id = message.id, chatId = message.chatId, messageSeq = message.messageSeq, type = "SYSTEM",
        authorId = null, authorName = null, authorAvatarObjectKey = null, body = null,
        systemType = draft.kind.databaseValue, systemPayload = buildJsonObject {
            put("kind", draft.kind.databaseValue)
            put("actorUserId", draft.actorUserId?.toString())
            put("pointId", draft.pointId.toString())
            put("chatId", draft.chatId.toString())
            put("proposalId", draft.proposalId?.toString())
            put("bookingId", draft.bookingId?.toString())
            put("startsAt", draft.startsAt?.toString())
            put("endsAt", draft.endsAt?.toString())
            put("reason", draft.reason?.databaseValue)
        }, createdAt = message.createdAt,
    )
}

data class CreateProposalCommand(
    val actorId: UUID, val chatId: UUID, val idempotencyKey: UUID,
    val startsAt: Instant, val endsAt: Instant,
)

sealed interface CreateProposalResult {
    data class Created(val proposal: ProposalView, val responseSnapshot: JsonObject,
                       val emissions: List<MeetingEmission>) : CreateProposalResult
    data class Replay(val responseSnapshot: JsonObject) : CreateProposalResult
    data object KeyReused : CreateProposalResult
    data object ChatGone : CreateProposalResult
    data object NotMember : CreateProposalResult
    data object InvalidInterval : CreateProposalResult
}

data class VoteCommand(val actorId: UUID, val proposalId: UUID, val going: Boolean)

sealed interface VoteResult {
    data class Updated(val proposal: ProposalView, val emissions: List<MeetingEmission>,
                       val bookingChange: BookingChange? = null) : VoteResult
    data class Unchanged(val proposal: ProposalView) : VoteResult
    data object ProposalGone : VoteResult
    data object NotMember : VoteResult
    data object Closed : VoteResult
    data object Started : VoteResult
}
