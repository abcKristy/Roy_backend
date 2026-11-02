package com.example.chats

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signed, scoped keyset cursor; only the query fingerprint is exposed. */
internal class ChatCursorCodec(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    init { require(secret.toByteArray(StandardCharsets.UTF_8).size >= 32) }

    fun encode(scope: String, fingerprint: String, createdAt: Instant, id: UUID, upperSeq: Long? = null): String {
        val payload = "1|$scope|$fingerprint|${upperSeq ?: "-"}|$createdAt|$id".toByteArray(StandardCharsets.UTF_8)
        return encoder.encodeToString(payload) + "." + encoder.encodeToString(sign(payload))
    }

    fun decode(scope: String, fingerprint: String, cursor: String): Triple<Instant, UUID, Long?> {
        require(cursor.length <= 1024) { "Invalid cursor" }
        val parts = cursor.split('.')
        require(parts.size == 2) { "Invalid cursor" }
        val payload = try { decoder.decode(parts[0]) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid cursor") }
        val signature = try { decoder.decode(parts[1]) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid cursor") }
        require(MessageDigest.isEqual(sign(payload), signature)) { "Invalid cursor" }
        val values = payload.toString(StandardCharsets.UTF_8).split('|')
        require(values.size == 6 && values[0] == "1" && values[1] == scope && values[2] == fingerprint) { "Invalid cursor" }
        val upper = if (values[3] == "-") null else values[3].toLongOrNull() ?: throw IllegalArgumentException("Invalid cursor")
        val at = try { Instant.parse(values[4]) } catch (_: Exception) { throw IllegalArgumentException("Invalid cursor") }
        val id = try { UUID.fromString(values[5]) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid cursor") }
        return Triple(at, id, upper)
    }

    private fun sign(payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(payload)
}
