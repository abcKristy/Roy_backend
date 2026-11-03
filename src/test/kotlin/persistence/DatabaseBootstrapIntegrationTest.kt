package com.example.persistence

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseBootstrapIntegrationTest {
    @Test
    fun `local migration and production validation work with a short connection timeout`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url)) {
            "TEST_DATABASE_URL must point to a disposable roy_m1t2_ database"
        }
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.LOCAL))
            .start().use { assertTrue(it.isReady()) }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.LOCAL))
            .start().use { assertTrue(it.isReady()) }

        val runtime = HikariDatabaseBootstrap(
            ServerConfig(
                url, user, password, "test-only-secret",
                appEnvironment = AppEnvironment.PRODUCTION,
                databaseConnectionTimeoutMs = 800,
            )
        ).start()
        runtime.use {
            assertTrue(it.isReady())
            assertNotNull(it.poolSnapshot())
            val settings = it.withReadOnlyJdbcTransaction { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_setting('transaction_read_only'), current_setting('transaction_isolation')")
                        .use { rows ->
                            assertTrue(rows.next())
                            rows.getString(1) to rows.getString(2)
                        }
                }
            }
            assertEquals("on" to "repeatable read", settings)

            var attempts = 0
            val result = it.withRetryingJdbcTransaction { connection ->
                attempts++
                if (attempts == 1) throw SQLException("simulated deadlock", "40P01")
                connection.acquireTransactionAdvisoryLock(AdvisoryLockKeys.pointCreation)
                attempts
            }
            assertEquals(2, result)
        }
        DriverManager.getConnection(url, user, password).use { connection ->
            try {
                connection.acquireTransactionAdvisoryLock(AdvisoryLockKeys.pointCreation)
                error("Autocommit advisory lock unexpectedly succeeded")
            } catch (_: IllegalArgumentException) {
                // Transaction-scoped locks must be called inside an explicit transaction.
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(14, rows.getInt(1))
                }
            }
        }
    }
}
