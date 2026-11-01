package com.example.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Shares a bounded pool of expensive password operations across auth services. */
class PasswordHashingWork(
    private val hasher: PasswordHasher,
    maxConcurrent: Int = 2,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val slots = Semaphore(maxConcurrent)

    suspend fun hash(password: String): String = slots.withPermit {
        withContext(dispatcher) { hasher.hash(password) }
    }

    suspend fun verify(password: String, encodedHash: String): Boolean = slots.withPermit {
        withContext(dispatcher) { hasher.verify(password, encodedHash) }
    }

    suspend fun verifyUnknown(password: String) = slots.withPermit {
        withContext(dispatcher) { hasher.verifyUnknown(password) }
    }

    fun needsRehash(encodedHash: String): Boolean = hasher.needsRehash(encodedHash)
}
