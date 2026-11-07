package com.example.external

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.jobs.PushFailureCode
import com.example.jobs.PushPayload
import com.example.jobs.PushSendResult
import com.example.jobs.PushSender
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

enum class ApnsEnvironment(internal val host: String) {
    SANDBOX("api.sandbox.push.apple.com"),
    PRODUCTION("api.push.apple.com"),
}

class ApnsPushSender internal constructor(
    private val teamId: String,
    private val keyId: String,
    privateKeyPem: String,
    private val topic: String,
    private val environment: ApnsEnvironment,
    private val http: PushProviderHttp,
    private val clock: Clock,
) : PushSender {
    constructor(teamId: String, keyId: String, privateKeyPem: String, topic: String, environment: ApnsEnvironment) :
        this(teamId, keyId, privateKeyPem, topic, environment,
            JavaPushProviderHttp(requireHttp2 = true), Clock.systemUTC())

    init {
        val identifier = Regex("[A-Z0-9]{10}")
        require(teamId.matches(identifier) && keyId.matches(identifier)) { "Invalid APNs Team ID or Key ID" }
        require(topic.matches(Regex("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"))) { "Invalid APNs topic" }
    }

    private val algorithm = Algorithm.ECDSA256(null, readKey(privateKeyPem))
    private data class SignedToken(val issuedAt: Instant, val value: String)
    private var cachedToken: SignedToken? = null

    override suspend fun send(platform: String, token: String, payload: PushPayload): PushSendResult {
        if (platform != "IOS_APNS") return PushSendResult.Permanent(PushFailureCode.CONFIGURATION)
        if (token.length % 2 != 0 || !token.matches(Regex("[A-Fa-f0-9]{2,4096}"))) {
            return PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN)
        }
        val authorization = providerToken()
        val headers = mapOf(
            "Authorization" to "bearer $authorization",
            "apns-topic" to topic,
            "apns-push-type" to "alert",
            "apns-expiration" to "0",
            "apns-id" to payload.notificationId.toString(),
            "apns-collapse-id" to payload.notificationId.toString(),
        )
        val body = buildJsonObject {
            put("aps", buildJsonObject { put("alert", payload.providerAlert()) })
            payload.providerData(JsonPrimitive(1)).forEach { (key, value) -> put(key, value) }
        }
        return http.sendPush(URI.create("https://${environment.host}/3/device/$token"), headers, body) {
            classify(it, authorization)
        }
    }

    @Synchronized
    private fun providerToken(): String {
        val now = clock.instant()
        cachedToken?.let {
            if (now >= it.issuedAt && now < it.issuedAt.plus(Duration.ofMinutes(45))) return it.value
        }
        val value = JWT.create().withKeyId(keyId).withIssuer(teamId).withIssuedAt(now).sign(algorithm)
        cachedToken = SignedToken(now, value)
        return value
    }

    @Synchronized
    private fun invalidate(authorization: String) {
        if (cachedToken?.value == authorization) cachedToken = null
    }

    private fun classify(response: PushProviderResponse, authorization: String): PushSendResult {
        val reason = response.jsonBody()?.string("reason")
        return when {
            response.status == 200 && response.body.isBlank() -> PushSendResult.Delivered
            response.status == 410 || reason in setOf("Unregistered", "ExpiredToken") ->
                PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN)
            reason == "BadDeviceToken" -> PushSendResult.Permanent(PushFailureCode.REJECTED)
            reason == "ExpiredProviderToken" -> {
                invalidate(authorization)
                PushSendResult.Retryable(PushFailureCode.UNAVAILABLE)
            }
            reason == "TooManyProviderTokenUpdates" -> PushSendResult.Retryable(PushFailureCode.RATE_LIMITED, 1200)
            response.status == 429 -> PushSendResult.Retryable(PushFailureCode.RATE_LIMITED)
            response.status >= 500 -> PushSendResult.Retryable(PushFailureCode.UNAVAILABLE, 900)
            response.status == 408 || reason == "IdleTimeout" -> PushSendResult.Retryable(PushFailureCode.TIMEOUT)
            response.status == 403 || reason in setOf("BadTopic", "MissingTopic", "TopicDisallowed", "DeviceTokenNotForTopic") ->
                PushSendResult.Permanent(PushFailureCode.CONFIGURATION)
            response.status == 200 -> PushSendResult.Retryable(PushFailureCode.UNAVAILABLE)
            else -> PushSendResult.Permanent(PushFailureCode.REJECTED)
        }
    }

    private fun readKey(pem: String): ECPrivateKey = try {
        val body = pem.trim()
        val header = "-----BEGIN PRIVATE KEY-----"
        val footer = "-----END PRIVATE KEY-----"
        require(body.startsWith(header) && body.endsWith(footer))
        val encoded = body.removePrefix(header).removeSuffix(footer).filterNot(Char::isWhitespace)
        val decoded = Base64.getDecoder().decode(encoded)
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoded)) as ECPrivateKey
        val p256 = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        require(key.params.curve == p256.curve && key.params.generator == p256.generator &&
            key.params.order == p256.order && key.params.cofactor == p256.cofactor)
        key
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid APNs PKCS#8 P-256 private key")
    }
}
