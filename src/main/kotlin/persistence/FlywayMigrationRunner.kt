package com.example.persistence

import com.example.config.ServerConfig
import org.flywaydb.core.Flyway

/** Runs immutable SQL migrations before the application accepts requests. */
class FlywayMigrationRunner(private val config: ServerConfig) {
    fun migrate() {
        Flyway.configure()
            .dataSource(config.databaseUrl, config.databaseUser, config.databasePassword)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
