package com.example.api

import com.example.auth.SessionAccessService
import com.example.points.PointMapFilter
import com.example.points.PointPageRead
import com.example.points.PointPreviewRead
import com.example.points.PointQuotaRead
import com.example.points.PointRead
import com.example.points.PointReadRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class PositionResponse(val lat: Double, val lon: Double)

@Serializable
private data class TopicResponse(
    val id: String, val presetKey: String, val label: String,
    val participantCount: Int, val chatId: String,
)

@Serializable
private data class PointResponse(
    val id: String, val position: PositionResponse, val address: String,
    val addressSource: String, val peopleCount: Int, val topics: List<TopicResponse>,
    val createdAt: String, val updatedAt: String,
)

@Serializable
private data class PointPageResponse(val items: List<PointResponse>, val nextCursor: String?)

@Serializable
private data class TopicPresetResponse(val key: String, val label: String)

@Serializable
private data class TopicPresetListResponse(val items: List<TopicPresetResponse>)

@Serializable
private data class PointPreviewResponse(
    val id: String, val position: PositionResponse, val address: String, val distanceMeters: Int,
)

@Serializable
private data class PointQuotaResponse(
    val weekUsed: Int, val weekLimit: Int, val weekRemaining: Int, val weekNextAvailableAt: String?,
    val monthUsed: Int, val monthLimit: Int, val monthRemaining: Int, val monthNextAvailableAt: String?,
    val blockedUntil: String?, val lastAvailableWarning: Boolean,
)

@Serializable
private data class PointCreationContextResponse(val nearby: List<PointPreviewResponse>, val quota: PointQuotaResponse)

internal fun Route.pointReadRoutes(read: PointReadRepository, sessions: SessionAccessService?) {
    get("/api/v1/topics/presets") {
        call.respond(TopicPresetListResponse(read.presets().map { TopicPresetResponse(it.key, it.label) }))
    }
    get("/api/v1/points") {
        val params = call.request.queryParameters
        fun number(name: String): Double = params.getAll(name)?.singleOrNull()?.toDoubleOrNull()
            ?: throw invalidBbox()
        fun filterValue(name: String): String? = params.getAll(name)?.let { values ->
            values.singleOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный фильтр")
        }
        fun integer(name: String, default: Int): Int = filterValue(name)?.toIntOrNull()
            ?: if (params.getAll(name) == null) default
            else throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный фильтр")
        val west = number("west")
        val south = number("south")
        val east = number("east")
        val north = number("north")
        if (listOf(west, south, east, north).any { !it.isFinite() } ||
            west !in -180.0..180.0 || east !in -180.0..180.0 ||
            south !in -90.0..90.0 || north !in -90.0..90.0 || west >= east || south >= north) {
            throw invalidBbox()
        }
        val filter = try {
            PointMapFilter(
                west = west, south = south, east = east, north = north,
                topicKey = filterValue("topicKey"), customTopicLabel = filterValue("customTopicLabel"),
                peopleMin = integer("peopleMin", 0), peopleMax = integer("peopleMax", 50),
                limit = integer("limit", 30),
            )
        } catch (_: IllegalArgumentException) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный фильтр")
        }
        val cursors = params.getAll("cursor")
        if (cursors != null && cursors.size != 1) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Некорректный курсор")
        }
        val page = try { read.page(filter, cursors?.single()) } catch (_: IllegalArgumentException) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Некорректный курсор")
        }
        call.respond(page.toResponse())
    }
    if (sessions != null) {
        get("/api/v1/points/creation-context") {
            val actor = UUID.fromString(sessions.currentUser(call.request.headers[HttpHeaders.Authorization]).id)
            val lat = call.request.queryParameters["lat"]?.toDoubleOrNull() ?: throw invalidBbox()
            val lon = call.request.queryParameters["lon"]?.toDoubleOrNull() ?: throw invalidBbox()
            val context = try { read.creationContext(actor, lat, lon) } catch (_: IllegalArgumentException) {
                throw invalidBbox()
            }
            call.respond(PointCreationContextResponse(context.nearby.map { it.toResponse() }, context.quota.toResponse()))
        }
        get("/api/v1/me/point-quota") {
            val actor = UUID.fromString(sessions.currentUser(call.request.headers[HttpHeaders.Authorization]).id)
            call.respond(read.quota(actor).toResponse())
        }
    }
    get("/api/v1/points/{pointId}") {
        val id = try { UUID.fromString(call.parameters["pointId"]) } catch (_: IllegalArgumentException) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_FIELD", "Некорректный идентификатор")
        }
        val point = read.detail(id) ?: throw ApiException(HttpStatusCode.NotFound, "RESOURCE_GONE", "Точка не найдена")
        call.respond(point.toResponse())
    }
}

private fun invalidBbox() = ApiException(HttpStatusCode.BadRequest, "INVALID_BBOX", "Некорректные координаты")

private fun PointPageRead.toResponse() = PointPageResponse(items.map { it.toResponse() }, nextCursor)

private fun PointRead.toResponse() = PointResponse(
    id.toString(), PositionResponse(lat, lon), address,
    when (addressSource) { "GEOCODED" -> "provider"; "MANUAL" -> "manual"; else -> addressSource },
    peopleCount, topics.map { TopicResponse(it.id.toString(), it.presetKey, it.label, it.participantCount, it.chatId.toString()) },
    createdAt.toString(), updatedAt.toString(),
)

private fun PointPreviewRead.toResponse() = PointPreviewResponse(
    id.toString(), PositionResponse(lat, lon), address, distanceMeters,
)

private fun PointQuotaRead.toResponse() = PointQuotaResponse(
    weekUsed, weekLimit, weekRemaining, weekNextAvailableAt?.toString(),
    monthUsed, monthLimit, monthRemaining, monthNextAvailableAt?.toString(),
    blockedUntil?.toString(), lastAvailableWarning,
)
