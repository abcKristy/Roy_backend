package com.example.devices

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceTokenCipherTest {
    private val cipher = DeviceTokenCipher.fromBase64(fixtureKey())
    private val installation = UUID.randomUUID()
    private val platform = "ANDROID_RUSTORE"
    private val token = "fixture-${UUID.randomUUID()}"

    @Test
    fun `configuration rejects a base64 key of the wrong size`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(31).also(SecureRandom()::nextBytes))
        val failure = assertFailsWith<IllegalArgumentException> { DeviceTokenCipher.fromBase64(shortKey) }
        assertEquals("PUSH_TOKEN_ENCRYPTION_KEY_BASE64 must encode 32 bytes", failure.message)
        assertFalse(failure.stackTraceToString().contains(shortKey), "Configuration failure exposed its input")
    }

    @Test
    fun `repeated encryption uses a fresh nonce and decrypts the original token`() {
        val first = cipher.encrypt(installation, platform, token)
        val second = cipher.encrypt(installation, platform, token)
        assertTrue(first.startsWith("v1:"))
        val firstBytes = Base64.getDecoder().decode(first.removePrefix("v1:"))
        val secondBytes = Base64.getDecoder().decode(second.removePrefix("v1:"))
        assertEquals(12 + token.toByteArray().size + 16, firstBytes.size)
        assertFalse(firstBytes.copyOf(12).contentEquals(secondBytes.copyOf(12)), "Nonce reused")
        assertTrue(cipher.decrypt(installation, platform, first) == token, "First round trip failed")
        assertTrue(cipher.decrypt(installation, platform, second) == token, "Second round trip failed")
    }

    @Test
    fun `authentication rejects a different installation or platform`() {
        val stored = cipher.encrypt(installation, platform, token)
        assertFailsWith<AEADBadTagException> { cipher.decrypt(UUID.randomUUID(), platform, stored) }
        assertFailsWith<AEADBadTagException> { cipher.decrypt(installation, "IOS_APNS", stored) }
    }

    @Test
    fun `authentication rejects tampering with encrypted token bytes`() {
        val stored = cipher.encrypt(installation, platform, token)
        val bytes = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        bytes[12] = (bytes[12].toInt() xor 1).toByte()
        val tampered = "v1:" + Base64.getEncoder().encodeToString(bytes)
        assertFailsWith<AEADBadTagException> { cipher.decrypt(installation, platform, tampered) }
    }
}

internal fun fixtureKey(): String =
    Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
