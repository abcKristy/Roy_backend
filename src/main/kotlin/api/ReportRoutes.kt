package com.example.api

import com.example.auth.SessionAccessService
import com.example.moderation.ModerationService
import com.example.moderation.ReportCommand
import com.example.moderation.ReportResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
private data class ReportInput(val contextChatId: String, val targetUserId: String, val reasonText: String)

internal fun Route.reportRoutes(service: ModerationService, sessions: SessionAccessService) {
    post("/api/v1/reports") {
        val actor = UUID.fromString(sessions.currentUser(call.request.headers[HttpHeaders.Authorization]).id)
        val key = reportUuid(call.request.headers["Idempotency-Key"], HttpStatusCode.BadRequest)
        val input = call.receiveStrictJson(ReportInput.serializer())
        val target = reportUuid(input.targetUserId, HttpStatusCode.UnprocessableEntity)
        val reason = input.reasonText.trim()
        if (actor == target || reason.codePointCount(0, reason.length) !in 1..1000) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_FIELD", "Некорректная жалоба")
        }
        val result = service.report(ReportCommand(actor, key,
            reportUuid(input.contextChatId, HttpStatusCode.UnprocessableEntity), target, reason))
        when (result) {
            is ReportResult.Created -> call.respondReportSnapshot(result.responseSnapshot)
            is ReportResult.Replay -> call.respondReportSnapshot(result.responseSnapshot)
            ReportResult.KeyReused -> throw ApiException(HttpStatusCode.Conflict,
                "IDEMPOTENCY_KEY_REUSED", "Ключ запроса уже использован")
            ReportResult.DuplicateReport -> throw ApiException(HttpStatusCode.Conflict,
                "DUPLICATE_REPORT", "Жалоба уже отправлена")
            ReportResult.Forbidden -> throw ApiException(HttpStatusCode.Forbidden,
                "FORBIDDEN", "Жалоба недоступна")
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondReportSnapshot(snapshot: JsonObject) {
    val status = snapshot["status"]?.jsonPrimitive?.int ?: error("Saved report has no status")
    val body = snapshot["body"] ?: error("Saved report has no body")
    respond(HttpStatusCode.fromValue(status), body)
}

private fun reportUuid(raw: String?, status: HttpStatusCode): UUID {
    val value = raw ?: throw ApiException(status, "INVALID_FIELD", "Некорректный идентификатор")
    return try { UUID.fromString(value) } catch (_: IllegalArgumentException) {
        throw ApiException(status, "INVALID_FIELD", "Некорректный идентификатор")
    }
}
