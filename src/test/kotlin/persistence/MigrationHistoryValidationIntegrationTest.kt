package com.example.persistence

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationHistoryValidationIntegrationTest {
    @Test
    fun `pending and changed checksums prevent startup while repeat migration preserves seed`() {
        val url = com.example.testing.testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_history$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('public.flyway_schema_history') IS NULL").use {
                    check(it.next() && it.getBoolean(1)) { "Fresh disposable migration database required" }
                }
            }
        }
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
            .target("013").load().migrate()
        fun config(mode: AppEnvironment) = ServerConfig(url, user, password, "test-only", appEnvironment = mode)
        val pending = assertFailsWith<org.flywaydb.core.api.exception.FlywayValidateException> {
            HikariDatabaseBootstrap(config(AppEnvironment.PRODUCTION)).start().close()
        }
        assertTrue(pending.message.orEmpty().contains("Detected resolved migration not applied to database: 014"))
        HikariDatabaseBootstrap(config(AppEnvironment.LOCAL)).start().close()
        fun seedCount(): Int = DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM topic_presets").use { it.next(); it.getInt(1) }
            }
        }
        val before = seedCount()
        HikariDatabaseBootstrap(config(AppEnvironment.LOCAL)).start().close()
        assertEquals(before, seedCount())
        HikariDatabaseBootstrap(config(AppEnvironment.PRODUCTION)).start().close()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(1, statement.executeUpdate("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE installed_rank = 1"))
                try {
                    for (mode in listOf(AppEnvironment.PRODUCTION, AppEnvironment.LOCAL)) {
                        val mismatch = assertFailsWith<Exception> {
                            HikariDatabaseBootstrap(config(mode)).start().close()
                        }
                        assertTrue(generateSequence<Throwable>(mismatch) { it.cause }
                            .any { it.message?.contains("checksum", ignoreCase = true) == true },
                            "Startup must fail because of the changed migration checksum")
                    }
                } finally {
                    assertEquals(1, statement.executeUpdate("UPDATE flyway_schema_history SET checksum = checksum - 1 WHERE installed_rank = 1"))
                }
            }
        }
        HikariDatabaseBootstrap(config(AppEnvironment.PRODUCTION)).start().close()
    }
}
