package com.example.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = Argon2idPasswordHasher()

    @Test
    fun `hashes have independent salts and reject another password`() {
        val first = hasher.hash("a-long-password-for-roy")
        val second = hasher.hash("a-long-password-for-roy")

        assertNotEquals(first, second)
        assertTrue(first.startsWith("\$argon2id\$v=19\$"))
        assertTrue(hasher.verify("a-long-password-for-roy", first))
        assertFalse(hasher.verify("another-password", first))
        assertFalse(hasher.needsRehash(first))
        hasher.verifyUnknown("a-long-password-for-roy")
    }

    @Test
    fun `weaker valid cost verifies and requests rehash`() {
        val previous = Argon2idPasswordHasher(Argon2Cost(memoryKiB = 19_456, iterations = 1))
        val stored = previous.hash("a-long-password-for-roy")

        assertTrue(hasher.verify("a-long-password-for-roy", stored))
        assertTrue(hasher.needsRehash(stored))
        assertFalse(hasher.verify("a-long-password-for-roy", "corrupt-hash"))
    }

    @Test
    fun `stronger stored cost is not downgraded`() {
        val stronger = Argon2idPasswordHasher(Argon2Cost(memoryKiB = 65_536, iterations = 3))
        val stored = stronger.hash("a-long-password-for-roy")

        assertTrue(hasher.verify("a-long-password-for-roy", stored))
        assertFalse(hasher.needsRehash(stored))
    }

    @Test
    fun `incomparable stored cost is retained`() {
        val incomparable = Argon2idPasswordHasher(Argon2Cost(memoryKiB = 46_080, iterations = 1))
        val stored = incomparable.hash("a-long-password-for-roy")

        assertTrue(hasher.verify("a-long-password-for-roy", stored))
        assertFalse(hasher.needsRehash(stored))
    }
}
