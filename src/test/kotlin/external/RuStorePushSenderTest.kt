package com.example.external

import com.example.jobs.PushFailureCode
import com.example.jobs.PushPayload
import com.example.jobs.PushSendResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpTimeoutException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuStorePushSenderTest {
    private val payload = PushPayload(UUID.randomUUID(), "nearby_meeting", UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `RuStore posts bearer service credentials and only public nearby targets`() = runBlocking {
        val sender = RuStorePushSender("my-project_1", "synthetic-key", PushProviderHttp { uri, headers, body ->
            assertEquals("https://vkpns.rustore.ru/v1/projects/my-project_1/messages:send", uri.toString())
            assertEquals("Bearer synthetic-key", headers["Authorization"])
            val message = Json.parseToJsonElement(body).jsonObject.getValue("message").jsonObject
            assertEquals("synthetic-token", message.getValue("token").jsonPrimitive.content)
            assertTrue(message.getValue("notification").jsonObject.getValue("title").jsonPrimitive.content.isNotBlank())
            val data = message.getValue("data").jsonObject
            assertEquals(setOf("v", "notificationId", "kind", "pointId"), data.keys)
            assertEquals(kotlinx.serialization.json.JsonPrimitive("1"), data.getValue("v"))
            assertEquals(payload.pointId.toString(), data.getValue("pointId").jsonPrimitive.content)
            PushProviderResponse(200, "{}")
        })
        assertEquals(PushSendResult.Delivered, sender.send("ANDROID_RUSTORE", "synthetic-token", payload))
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
