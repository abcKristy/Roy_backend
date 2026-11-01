package com.example.persistence

/** Lifecycle boundary for migration and connection setup. The JDBC implementation follows later. */
interface DatabaseBootstrap {
    /** Must fail startup when migration or connection setup fails. */
    fun start(): DatabaseRuntime
}

interface DatabaseRuntime : AutoCloseable {
    /** A fresh probe of mandatory database availability, not a cached startup result. */
    fun isReady(): Boolean
}
