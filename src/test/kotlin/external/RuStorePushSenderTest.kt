package com.example.external

import com.example.jobs.PushFailureCode
import com.example.jobs.PushPayload
import com.example.jobs.PushSendResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpTimeoutException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RuStorePushSenderTest {
    private val payload = PushPayload(UUID.randomUUID(), "0123456789abcdef".repeat(4), "nearby_meeting", UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `RuStore posts nearby data-only with bearer service credentials and public targets`() = runBlocking {
        val sender = RuStorePushSender("my-project_1", "synthetic-key", PushProviderHttp { uri, headers, body ->
            assertEquals("https://vkpns.rustore.ru/v1/projects/my-project_1/messages:send", uri.toString())
            assertEquals("Bearer synthetic-key", headers["Authorization"])
            val message = Json.parseToJsonElement(body).jsonObject.getValue("message").jsonObject
            assertEquals(setOf("token", "data", "android"), message.keys)
            assertEquals("synthetic-token", message.getValue("token").jsonPrimitive.content)
            val android = message.getValue("android").jsonObject
            assertEquals(JsonObject(mapOf("ttl" to JsonPrimitive("3600s"))), android)
            val data = message.getValue("data").jsonObject
            assertEquals(JsonObject(mapOf(
                "v" to JsonPrimitive("1"),
                "notificationId" to JsonPrimitive(payload.notificationId.toString()),
                "recipientBinding" to JsonPrimitive(payload.recipientBinding),
                "kind" to JsonPrimitive("nearby_meeting"),
                "pointId" to JsonPrimitive(payload.pointId.toString()),
            )), data)
            PushProviderResponse(200, "{}")
        })
        assertEquals(PushSendResult.Delivered, sender.send("ANDROID_RUSTORE", "synthetic-token", payload))
    }

    @Test
    fun `RuStore reminder data preserves the typed tap target and short TTL`() = runBlocking {
        val reminder = payload.copy(kind = "reminder_1h")
        val sender = RuStorePushSender("project", "synthetic-key", PushProviderHttp { _, _, body ->
            val message = Json.parseToJsonElement(body).jsonObject.getValue("message").jsonObject
            assertEquals(setOf("token", "data", "android"), message.keys)
            assertEquals(JsonObject(mapOf("ttl" to JsonPrimitive("120s"))), message.getValue("android").jsonObject)
            assertEquals(JsonObject(mapOf(
                "v" to JsonPrimitive("1"),
                "notificationId" to JsonPrimitive(reminder.notificationId.toString()),
                "recipientBinding" to JsonPrimitive(reminder.recipientBinding),
                "kind" to JsonPrimitive("reminder_1h"),
                "pointId" to JsonPrimitive(reminder.pointId.toString()),
                "chatId" to JsonPrimitive(reminder.chatId.toString()),
                "proposalId" to JsonPrimitive(reminder.proposalId.toString()),
            )), message.getValue("data").jsonObject)
            PushProviderResponse(200, "{}")
        })
        assertEquals(PushSendResult.Delivered, sender.send("ANDROID_RUSTORE", "synthetic-token", reminder))
    }

    @Test
    fun `RuStore distinguishes token rejection configuration throttling and timeout`() = runBlocking {
        var response = PushProviderResponse(404, """{"error":{"status":"NOT_FOUND"}}""")
        val sender = RuStorePushSender("project", "synthetic-key", PushProviderHttp { _, _, _ -> response })
        suspend fun send() = sender.send("ANDROID_RUSTORE", "synthetic-token", payload)
        assertEquals(PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN), send())
        response = PushProviderResponse(400,
            """{"error":{"status":"INVALID_ARGUMENT","message":"The registration token is not a valid FCM registration token"}}""")
        assertEquals(PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN), send())
        response = PushProviderResponse(401, """{"error":{"status":"PERMISSION_DENIED"}}""")
        assertEquals(PushSendResult.Permanent(PushFailureCode.CONFIGURATION), send())
        response = PushProviderResponse(429, """{"error":{"status":"TOO_MANY_REQUESTS"}}""")
        assertEquals(PushSendResult.Retryable(PushFailureCode.RATE_LIMITED), send())
        response = PushProviderResponse(500, "")
        assertEquals(PushSendResult.Retryable(PushFailureCode.UNAVAILABLE), send())
        response = PushProviderResponse(200, """{"error":{"status":"INTERNAL"}}""")
        assertEquals(PushSendResult.Retryable(PushFailureCode.UNAVAILABLE), send())
        val timeout = RuStorePushSender("project", "synthetic-key", PushProviderHttp { _, _, _ ->
            throw HttpTimeoutException("synthetic timeout")
        })
        assertEquals(PushSendResult.Retryable(PushFailureCode.TIMEOUT),
            timeout.send("ANDROID_RUSTORE", "synthetic-token", payload))
    }
}
