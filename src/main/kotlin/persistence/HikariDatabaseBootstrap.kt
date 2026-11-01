package com.example.persistence

import com.example.config.ServerConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class HikariDatabaseBootstrap(private val config: ServerConfig) : DatabaseBootstrap {
    override fun start(): DatabaseRuntime {
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = 5
            minimumIdle = 0
            connectionTimeout = 5_000
            validationTimeout = 3_000
            initializationFailTimeout = 5_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            poolName = "roy-db"
            addDataSourceProperty("socketTimeout", "5")
        })

        try {
            FlywayMigrationRunner(pool).migrate()
            val database = Database.connect(pool)
            return PooledDatabaseRuntime(pool, database)
        } catch (failure: Throwable) {
            pool.close()
            throw failure
        }
    }
}

private class PooledDatabaseRuntime(
    private val pool: HikariDataSource,
    private val database: Database,
) : DatabaseRuntime {
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
}
