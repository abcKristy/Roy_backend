package com.example.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHashBoundsTest {
    @Test
    fun `untrusted PHC costs and lengths outside policy are rejected`() {
        val hasher = Argon2idPasswordHasher()
        val encoded = hasher.hash("bounds-test-only-password")
        for (cost in listOf("m=19455,t=2,p=1", "m=65537,t=2,p=1", "m=19456,t=7,p=1",
            "m=19456,t=0,p=1", "m=19456,t=2,p=2", "m=99999999999999999999,t=2,p=1")) {
            val untrusted = encoded.replace("m=19456,t=2,p=1", cost)
            assertFalse(hasher.verify("bounds-test-only-password", untrusted), cost)
            assertTrue(hasher.needsRehash(untrusted), cost)
        }
        for (malformed in listOf(encoded + "x", encoded.dropLast(1), encoded.replace("v=19", "v=16"),
            encoded.replace("argon2id", "argon2i"))) {
            assertFalse(hasher.verify("bounds-test-only-password", malformed))
        }
    }
}
