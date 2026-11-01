package com.example.persistence

/** Lifecycle boundary for migration and connection setup. */
interface DatabaseBootstrap {
    /** Must fail startup when migration or connection setup fails. */
    fun start(): DatabaseRuntime
}

interface DatabaseRuntime : AutoCloseable {
    /** A fresh probe of mandatory database availability, not a cached startup result. */
    suspend fun isReady(): Boolean
}
