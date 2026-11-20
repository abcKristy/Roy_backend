package com.example.persistence

import com.example.config.ServerConfig
import com.example.config.AppEnvironment
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.PGConnection
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HikariDatabaseBootstrap(private val config: ServerConfig) : DatabaseBootstrap {
    override fun start(): DatabaseRuntime {
        if (config.appEnvironment == AppEnvironment.LOCAL) {
            // Local convenience only. A separate pool keeps DDL out of the short runtime socket timeout.
            migrationPool(
                config.databaseUrl,
                config.migrationUser ?: config.databaseUser,
                config.migrationPassword ?: config.databasePassword,
            ).use { FlywayMigrationRunner(it).migrate() }
        }
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = config.databaseMaxPoolSize
            minimumIdle = 0
            connectionTimeout = config.databaseConnectionTimeoutMs
            validationTimeout = minOf(3_000L, config.databaseConnectionTimeoutMs / 2)
            initializationFailTimeout = 5_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            poolName = "roy-db"
            isRegisterMbeans = config.databasePoolJmxEnabled
            addDataSourceProperty("socketTimeout", ((config.databaseStatementTimeoutMs + 999) / 1_000 + 2).toString())
            addDataSourceProperty("cancelSignalTimeout", "2")
        })

        try {
            FlywayMigrationRunner(pool).requireCurrentSchema()
            val database = Database.connect(pool)
            return PooledDatabaseRuntime(pool, database, config)
        } catch (failure: Throwable) {
            pool.close()
            throw failure
        }
    }
}

internal fun migrationPool(url: String, user: String, secret: String): HikariDataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = url
    username = user
    password = secret
    // Flyway's PostgreSQL lock and migration work can hold two connections at once.
    maximumPoolSize = 2
    minimumIdle = 0
    connectionTimeout = 30_000
    validationTimeout = 5_000
    initializationFailTimeout = 30_000
    poolName = "roy-migrator"
    addDataSourceProperty("socketTimeout", "600")
})

private class PooledDatabaseRuntime(
    private val pool: HikariDataSource,
    private val database: Database,
    private val config: ServerConfig,
) : DatabaseRuntime {
    // Keep cancellation runnable even when ordinary IO threads are blocked borrowing pool connections.
    private val cancellationDispatcher = Dispatchers.IO.limitedParallelism(config.databaseMaxPoolSize)

    override suspend fun <T> withJdbcTransaction(block: (Connection) -> T): T =
        jdbcTransaction(readOnly = false, block)

    override suspend fun <T> withReadOnlyJdbcTransaction(block: (Connection) -> T): T =
        jdbcTransaction(readOnly = true, block)

    private suspend fun <T> jdbcTransaction(readOnly: Boolean, block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        ensureActive()
        // Borrowing remains bounded by connectionTimeout; never start SQL for an already cancelled caller.
        pool.connection.use { connection ->
            coroutineScope {
                ensureActive()
                val completed = AtomicBoolean(false)
                val abortFailure = AtomicReference<Exception?>()
                val cancellation = launch(cancellationDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!completed.get()) {
                            var failure: Exception? = null
                            try {
                                connection.unwrap(PGConnection::class.java).cancelQuery()
                            } catch (cancelFailure: Exception) {
                                failure = cancelFailure
                            }
                            try {
                                connection.abort { command -> command.run() }
                            } catch (closeFailure: Exception) {
                                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
                            }
                            abortFailure.set(failure)
                        }
                    }
                }
                var primaryFailure: Throwable? = null
                try {
                    if (readOnly) {
                        connection.isReadOnly = true
                        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                    }
                    connection.autoCommit = false
                    setTimeouts(connection)
                    val result = block(connection)
                    ensureActive()
                    connection.commit()
                    result
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    rollbackPreservingFailure(connection, failure)
                    throw failure
                } finally {
                    completed.set(true)
                    // Finish every cancellation action while this checkout is still owned by this transaction.
                    withContext(NonCancellable) { cancellation.cancelAndJoin() }
                    abortFailure.get()?.let { failure ->
                        if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
                    }
                }
            }
            // Hikari resets read-only and isolation on close; an aborted physical connection is discarded.
        }
    }

    private fun rollbackPreservingFailure(connection: Connection, failure: Throwable) {
        try {
            connection.rollback()
        } catch (rollbackFailure: Throwable) {
            if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
        }
    }

    private fun setTimeouts(connection: Connection) {
        connection.prepareStatement(
            "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)"
        ).use { statement ->
            statement.setString(1, "${config.databaseStatementTimeoutMs}ms")
            statement.setString(2, "${config.databaseLockTimeoutMs}ms")
            statement.execute()
        }
    }

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            transaction(database) {
                exec("SET LOCAL statement_timeout = '5000ms'")
                exec("SELECT 1") { result -> result.next() && result.getInt(1) == 1 } ?: false
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        pool.close()
    }

    override fun poolSnapshot(): DatabasePoolSnapshot? = pool.hikariPoolMXBean?.let {
        DatabasePoolSnapshot(it.activeConnections, it.idleConnections, it.threadsAwaitingConnection, it.totalConnections)
    }
}
