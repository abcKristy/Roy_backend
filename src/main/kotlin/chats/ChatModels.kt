package com.example.chats

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

data class ChatTopicView(val id: UUID, val presetKey: String, val label: String, val participantCount: Int, val chatId: UUID)
data class MessageView(
    val id: UUID, val chatId: UUID, val messageSeq: Long, val type: String,
    val authorId: UUID?, val authorName: String?, val authorAvatarObjectKey: String?,
    val body: String?, val systemType: String?, val systemPayload: JsonObject?, val createdAt: Instant,
)
data class MessagePreviewView(val id: UUID, val type: String, val text: String, val createdAt: Instant)
data class ProposalPreviewView(
    val id: UUID, val startsAt: Instant, val endsAt: Instant, val status: String,
    val goingCount: Int, val bookingId: UUID?, val revision: Long,
)
data class MembershipView(val chatId: UUID, val userId: UUID, val joinedAt: Instant, val lastReadSeq: Long)
data class ChatSummaryView(
    val id: UUID, val pointId: UUID, val topic: ChatTopicView, val address: String,
    val participantCount: Int, val lastMessage: MessagePreviewView?, val unreadCount: Long,
    val currentProposal: ProposalPreviewView?, val updatedAt: Instant,
)
data class ChatDetailView(val summary: ChatSummaryView, val membership: MembershipView, val lastMessageSeq: Long)
data class ChatPageView(val items: List<ChatSummaryView>, val nextCursor: String?)
data class MessageHistoryView(
    val items: List<MessageView>, val nextBeforeSeq: Long?, val nextAfterSeq: Long?,
)
data class MessageSearchView(val items: List<MessageView>, val nextCursor: String?)
sealed interface ReadCursorResult {
    data class Updated(val membership: MembershipView) : ReadCursorResult
    data object NotMember : ReadCursorResult
    data object BeyondLastMessage : ReadCursorResult
}

data class SendMessageCommand(val actorId: UUID, val chatId: UUID, val idempotencyKey: UUID, val text: String)
sealed interface SendMessageResult {
    data class Created(val message: MessageView, val responseSnapshot: JsonObject) : SendMessageResult
    data class Replay(val responseSnapshot: JsonObject) : SendMessageResult
    data object KeyReused : SendMessageResult
    data object NotMember : SendMessageResult
    data class Muted(val until: Instant) : SendMessageResult
    data class Flood(val retryAfterSeconds: Int) : SendMessageResult
    data object RejectedPattern : SendMessageResult
    data object InvalidText : SendMessageResult
}
