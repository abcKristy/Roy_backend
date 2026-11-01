package com.example.auth

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2

/** The application layer passes the raw password only to this boundary. */
interface PasswordHasher {
    fun hash(password: String): String
    fun verify(password: String, encodedHash: String): Boolean
    fun needsRehash(encodedHash: String): Boolean

    /** Perform a real hash check when a login phone has no matching user. */
    fun verifyUnknown(password: String)
}

/** Provisional OWASP minimum; production cost must be measured on its actual host. */
data class Argon2Cost(
    val memoryKiB: Int = 19_456,
    val iterations: Int = 2,
    val parallelism: Int = 1,
) {
    init {
        require(memoryKiB in 19_456..65_536)
        require(iterations in 1..6)
        require(parallelism == 1)
    }
}

class Argon2idPasswordHasher(cost: Argon2Cost = Argon2Cost()) : PasswordHasher {
    private val currentCost = cost
    private val function = Argon2Function.getInstance(
        cost.memoryKiB,
        cost.iterations,
        cost.parallelism,
        HASH_BYTES,
        Argon2.ID,
    )
    private val dummyHash = hash("not-a-user-${java.util.UUID.randomUUID()}")

    override fun hash(password: String): String = Password.hash(password)
        .addRandomSalt(SALT_BYTES)
        .with(function)
        .result

    override fun verify(password: String, encodedHash: String): Boolean {
        val parameters = parseParameters(encodedHash) ?: return false
        val verifier = Argon2Function.getInstance(
            parameters.memoryKiB,
            parameters.iterations,
            parameters.parallelism,
            HASH_BYTES,
            Argon2.ID,
        )
        return Password.check(password, encodedHash).with(verifier)
    }

    override fun needsRehash(encodedHash: String): Boolean {
        val stored = parseParameters(encodedHash) ?: return true
        // A stronger or incomparable stored cost must never be silently weakened.
        return stored.memoryKiB <= currentCost.memoryKiB &&
            stored.iterations <= currentCost.iterations &&
            (stored.memoryKiB < currentCost.memoryKiB || stored.iterations < currentCost.iterations)
    }

    override fun verifyUnknown(password: String) {
        verify(password, dummyHash)
    }

    private fun parseParameters(encodedHash: String): Argon2Cost? {
        val match = ENCODED_HASH.matchEntire(encodedHash) ?: return null
        val memory = match.groupValues[1].toIntOrNull() ?: return null
        val iterations = match.groupValues[2].toIntOrNull() ?: return null
        val parallelism = match.groupValues[3].toIntOrNull() ?: return null
        return try {
            Argon2Cost(memory, iterations, parallelism)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        // Bounds the work and encoded lengths before parsing a stored value.
        val ENCODED_HASH = Regex("""^\x24argon2id\x24v=19\x24m=([0-9]{1,5}),t=([0-9]),p=([0-9])\x24[A-Za-z0-9+/]{22}\x24[A-Za-z0-9+/]{43}\z""")
    }
}
