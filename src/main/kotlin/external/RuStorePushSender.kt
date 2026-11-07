package com.example.external

import com.example.jobs.PushFailureCode
import com.example.jobs.PushPayload
import com.example.jobs.PushSendResult
import com.example.jobs.PushSender
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

class RuStorePushSender internal constructor(
    projectId: String,
    private val serviceToken: String,
    private val http: PushProviderHttp,
) : PushSender {
    constructor(projectId: String, serviceToken: String) : this(projectId, serviceToken,
        JavaPushProviderHttp())

    init {
        require(projectId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid RuStore push project ID" }
        require(serviceToken.isNotEmpty() && serviceToken.all { it in '!'..'~' }) { "Invalid RuStore service token" }
    }

    private val endpoint = URI.create("https://vkpns.rustore.ru/v1/projects/$projectId/messages:send")

    override suspend fun send(platform: String, token: String, payload: PushPayload): PushSendResult {
        if (platform != "ANDROID_RUSTORE") return PushSendResult.Permanent(PushFailureCode.CONFIGURATION)
        if (token.isBlank()) return PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN)
        val body = buildJsonObject {
            put("message", buildJsonObject {
                put("token", token)
                put("notification", payload.providerAlert())
                put("data", payload.providerData(JsonPrimitive("1")))
                put("android", buildJsonObject { put("ttl", if (payload.kind == "reminder_1h") "120s" else "3600s") })
            })
        }
        return http.sendPush(endpoint, mapOf("Authorization" to "Bearer $serviceToken"), body, ::classify)
    }

    private fun classify(response: PushProviderResponse): PushSendResult {
        val body = response.jsonBody()
        val error = body?.get("error") as? JsonObject
        val status = error?.string("status")
        return when {
            response.status == 200 && (response.body.isBlank() || body != null && "error" !in body) ->
                PushSendResult.Delivered
            response.status == 429 || status == "TOO_MANY_REQUESTS" ->
                PushSendResult.Retryable(PushFailureCode.RATE_LIMITED)
            response.status == 408 -> PushSendResult.Retryable(PushFailureCode.TIMEOUT)
            response.status >= 500 || status == "INTERNAL" ->
                PushSendResult.Retryable(PushFailureCode.UNAVAILABLE)
            response.status in listOf(401, 403) || status == "PERMISSION_DENIED" ->
                PushSendResult.Permanent(PushFailureCode.CONFIGURATION)
            status in setOf("NOT_FOUND", "UNREGISTERED") ||
                (status == "INVALID_ARGUMENT" &&
                    error.string("message")?.startsWith("The registration token is not a valid ") == true) ->
                PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN)
            response.status == 200 -> PushSendResult.Retryable(PushFailureCode.UNAVAILABLE)
            else -> PushSendResult.Permanent(PushFailureCode.REJECTED)
        }
    }
}
