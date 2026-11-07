package com.example.external

import com.example.jobs.PushFailureCode
import com.example.jobs.PushPayload
import com.example.jobs.PushSendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

internal data class PushProviderResponse(val status: Int, val body: String)

internal fun interface PushProviderHttp {
    suspend fun post(uri: URI, headers: Map<String, String>, body: String): PushProviderResponse
}

internal class JavaPushProviderHttp(private val requireHttp2: Boolean = false) : PushProviderHttp {
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun post(uri: URI, headers: Map<String, String>, body: String): PushProviderResponse =
        runInterruptible(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            headers.forEach { (name, value) -> request.header(name, value) }
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            if (requireHttp2 && response.version() != HttpClient.Version.HTTP_2) {
                throw IOException("Provider did not negotiate HTTP/2")
            }
            PushProviderResponse(response.statusCode(), response.body())
        }
}

internal suspend fun PushProviderHttp.sendPush(
    uri: URI, headers: Map<String, String>, body: JsonObject, classify: (PushProviderResponse) -> PushSendResult,
): PushSendResult = try {
    classify(post(uri, headers, body.toString()))
} catch (_: HttpTimeoutException) {
    PushSendResult.Retryable(PushFailureCode.TIMEOUT)
} catch (_: IOException) {
    PushSendResult.Retryable(PushFailureCode.UNAVAILABLE)
}

internal fun PushProviderResponse.jsonBody(): JsonObject? =
    runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun PushPayload.providerData(version: JsonPrimitive): JsonObject = buildJsonObject {
    put("v", version)
    put("notificationId", notificationId.toString())
    put("kind", kind)
    pointId?.let { put("pointId", it.toString()) }
    if (kind != "nearby_meeting") {
        chatId?.let { put("chatId", it.toString()) }
        proposalId?.let { put("proposalId", it.toString()) }
    }
}

internal fun PushPayload.providerAlert(): JsonObject = buildJsonObject {
    put("title", "Рой")
    put("body", when (kind) {
        "nearby_meeting" -> "Предложение встречи рядом"
        "reminder_1h" -> "Напоминание о встрече"
        else -> "Новое уведомление"
    })
}
