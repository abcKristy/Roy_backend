package com.example.persistence

import com.example.config.ServerConfig
import com.example.config.AppEnvironment
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

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
            addDataSourceProperty("socketTimeout", ((config.databaseStatementTimeoutMs + 999) / 1_000 + 2).toString())
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
    override suspend fun <T> withJdbcTransaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                setTimeouts(connection)
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override suspend fun <T> withReadOnlyJdbcTransaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                setTimeouts(connection)
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.isReadOnly = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            }
        }
    }

    private fun setTimeouts(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL statement_timeout = ${config.databaseStatementTimeoutMs}")
            statement.execute("SET LOCAL lock_timeout = ${config.databaseLockTimeoutMs}")
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
