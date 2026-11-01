package com.example.points

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

data class PointTopicInput(val presetKey: String, val customLabel: String? = null)
data class PointPosition(val latitude: Double, val longitude: Double)
enum class PointAddressSource { GEOCODED, MANUAL }

data class CreatePointCommand(
    val actorId: UUID,
    val idempotencyKey: UUID,
    val position: PointPosition,
    val topic: PointTopicInput,
    val address: String,
    val addressSource: PointAddressSource,
    val confirmNearby: Boolean,
)

data class CreatedPoint(val pointId: UUID, val topicId: UUID, val chatId: UUID, val memberUserId: UUID, val joinedAt: Instant)
data class NearbyPoint(val id: UUID, val lat: Double, val lon: Double, val address: String, val distanceMeters: Int)

sealed interface CreatePointResult {
    data class Created(val value: CreatedPoint, val responseSnapshot: JsonObject) : CreatePointResult
    data class Replay(val responseSnapshot: JsonObject) : CreatePointResult
    data object KeyReused : CreatePointResult
    data class NearbyConfirmationRequired(val nearby: List<NearbyPoint>) : CreatePointResult
    data class QuotaExceeded(val blockedUntil: Instant, val responseSnapshot: JsonObject) : CreatePointResult
    data class QuotaBlocked(val blockedUntil: Instant) : CreatePointResult
}

data class AddTopicCommand(val actorId: UUID, val idempotencyKey: UUID, val pointId: UUID, val topic: PointTopicInput)
data class CreatedTopic(val pointId: UUID, val topicId: UUID, val chatId: UUID, val memberUserId: UUID, val joinedAt: Instant)
sealed interface AddTopicResult {
    data class Created(val value: CreatedTopic, val responseSnapshot: JsonObject) : AddTopicResult
    data class Replay(val responseSnapshot: JsonObject) : AddTopicResult
    data object KeyReused : AddTopicResult
    data object PointGone : AddTopicResult
    data object DuplicateTopic : AddTopicResult
}

data class PointMembership(val chatId: UUID, val userId: UUID, val joinedAt: Instant, val leftAt: Instant?, val lastReadSeq: Long)
sealed interface JoinPointChatResult {
    data class Joined(val membership: PointMembership) : JoinPointChatResult
    data class AlreadyMember(val membership: PointMembership) : JoinPointChatResult
    data object ChatGone : JoinPointChatResult
}

sealed interface LeavePointChatResult {
    data object Left : LeavePointChatResult
    data object AlreadyLeft : LeavePointChatResult
    data object ChatGone : LeavePointChatResult
}
