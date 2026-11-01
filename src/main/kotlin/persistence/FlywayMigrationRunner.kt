package com.example.persistence

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Runs immutable SQL migrations before the application accepts requests. */
class FlywayMigrationRunner(private val dataSource: DataSource) {
    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
