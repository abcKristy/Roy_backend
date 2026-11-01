package com.example.persistence

import java.sql.Connection

/** Lifecycle boundary for migration and connection setup. */
interface DatabaseBootstrap {
    /** Must fail startup when migration or connection setup fails. */
    fun start(): DatabaseRuntime
}

interface DatabaseRuntime : AutoCloseable {
    /** A fresh probe of mandatory database availability, not a cached startup result. */
    suspend fun isReady(): Boolean

    /** JDBC work runs off the event loop and rolls back as a unit on failure. */
    suspend fun <T> withJdbcTransaction(block: (Connection) -> T): T =
        throw UnsupportedOperationException("JDBC transactions are unavailable")

    /** Consistent multi-query read without write locks. Keep the transaction short. */
    suspend fun <T> withReadOnlyJdbcTransaction(block: (Connection) -> T): T =
        throw UnsupportedOperationException("Read-only JDBC transactions are unavailable")

    fun poolSnapshot(): DatabasePoolSnapshot? = null
}

data class DatabasePoolSnapshot(
    val active: Int,
    val idle: Int,
    val waiting: Int,
    val total: Int,
)
