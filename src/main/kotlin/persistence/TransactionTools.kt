package com.example.persistence

import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** The caller must keep external effects outside this retrying transaction. */
suspend fun <T> DatabaseRuntime.withRetryingJdbcTransaction(
    maxAttempts: Int = 3,
    block: (Connection) -> T,
): T {
    require(maxAttempts in 1..5)
    for (attempt in 1 until maxAttempts) {
        try {
            return withJdbcTransaction(block)
        } catch (failure: Throwable) {
            if (!failure.hasRetryableSqlState()) throw failure
            val ceiling = 25L shl (attempt - 1)
            delay(ThreadLocalRandom.current().nextLong(ceiling, ceiling * 2))
        }
    }
    return withJdbcTransaction(block)
}

private fun Throwable.hasRetryableSqlState(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SQLException && current.sqlState in setOf("40P01", "40001")) return true
        current = current.cause
    }
    return false
}

/** Stable, namespaced advisory keys; identifiers themselves are never placed in SQL literals. */
object AdvisoryLockKeys {
    val pointCreation: Long = key("point-creation", null)

    fun recipient(userId: UUID): Long = key("notification-recipient", userId)

    private fun key(namespace: String, id: UUID?): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$namespace:${id ?: "global"}".toByteArray(StandardCharsets.UTF_8)
        )
        return ByteBuffer.wrap(digest).long
    }
}

fun Connection.acquireTransactionAdvisoryLock(key: Long, shared: Boolean = false) {
    require(!autoCommit) { "Transaction advisory locks require an explicit transaction" }
    val sql = if (shared) "SELECT pg_advisory_xact_lock_shared(?)" else "SELECT pg_advisory_xact_lock(?)"
    prepareStatement(sql).use { statement ->
        statement.setLong(1, key)
        statement.executeQuery().use { rows -> check(rows.next()) }
    }
}
