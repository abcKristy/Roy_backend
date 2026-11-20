package com.example.external

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.jobs.PushFailureCode
import com.example.jobs.PushPayload
import com.example.jobs.PushSendResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApnsPushSenderTest {
    private val payload = PushPayload(UUID.randomUUID(), "0123456789abcdef".repeat(4), "nearby_meeting", UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `APNs signs ES256 and reuses the provider token across alert sends`() = runBlocking {
        val signedAt = Instant.parse("2026-11-07T12:00:00Z")
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val key = pem(keys.private.encoded)
        val tokens = mutableListOf<String>()
        val sender = ApnsPushSender("TEAM000001", "KEY0000001", key, "example.roy", ApnsEnvironment.SANDBOX,
            PushProviderHttp { uri, headers, body ->
                assertEquals("https://api.sandbox.push.apple.com/3/device/0123abcd", uri.toString())
                assertEquals("example.roy", headers["apns-topic"])
                assertEquals("alert", headers["apns-push-type"])
                assertEquals("0", headers["apns-expiration"])
                assertEquals(payload.notificationId.toString(), headers["apns-id"])
                val jwt = headers.getValue("Authorization").removePrefix("bearer ")
                val decoded = JWT.decode(jwt)
                assertEquals("ES256", decoded.algorithm)
                assertEquals("KEY0000001", decoded.keyId)
                assertEquals("TEAM000001", decoded.issuer)
                assertEquals(signedAt, decoded.issuedAtAsInstant)
                Algorithm.ECDSA256(keys.public as ECPublicKey, null).verify(decoded)
                tokens += jwt
                val data = Json.parseToJsonElement(body).jsonObject
                assertEquals(setOf("aps", "v", "notificationId", "kind", "pointId"), data.keys)
                assertEquals(kotlinx.serialization.json.JsonPrimitive(1), data.getValue("v"))
                PushProviderResponse(200, "")
            }, Clock.fixed(signedAt, ZoneOffset.UTC))
        repeat(2) { assertEquals(PushSendResult.Delivered, sender.send("IOS_APNS", "0123abcd", payload)) }
        assertEquals(1, tokens.distinct().size)
    }

    @Test
    fun `APNs validates the path token and maps provider response reasons`(): Unit = runBlocking {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        var response = PushProviderResponse(400, """{"reason":"BadDeviceToken"}""")
        val sender = ApnsPushSender("TEAM000001", "KEY0000001", pem(keys.private.encoded), "example.roy",
            ApnsEnvironment.PRODUCTION, PushProviderHttp { uri, _, _ ->
                assertEquals("api.push.apple.com", uri.host)
                response
            }, Clock.systemUTC())
        suspend fun send() = sender.send("IOS_APNS", "0123abcd", payload)
        assertEquals(PushSendResult.Permanent(PushFailureCode.REJECTED), send())
        response = PushProviderResponse(400, """{"reason":"DeviceTokenNotForTopic"}""")
        assertEquals(PushSendResult.Permanent(PushFailureCode.CONFIGURATION), send())
        response = PushProviderResponse(410, """{"reason":"Unregistered"}""")
        assertEquals(PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN), send())
        response = PushProviderResponse(403, """{"reason":"InvalidProviderToken"}""")
        assertEquals(PushSendResult.Permanent(PushFailureCode.CONFIGURATION), send())
        response = PushProviderResponse(429, """{"reason":"TooManyRequests"}""")
        assertEquals(PushSendResult.Retryable(PushFailureCode.RATE_LIMITED), send())
        response = PushProviderResponse(503, "")
        assertEquals(PushSendResult.Retryable(PushFailureCode.UNAVAILABLE, 900), send())
        assertEquals(PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN),
            sender.send("IOS_APNS", "a/b?secret", payload))
        assertFailsWith<IllegalArgumentException> {
            ApnsPushSender("TEAM000001", "KEY0000001", "wrong", "example.roy", ApnsEnvironment.PRODUCTION)
        }
    }

    private fun pem(key: ByteArray) = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getEncoder().encodeToString(key) + "\n-----END PRIVATE KEY-----"
}
