package com.example.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import javax.sql.DataSource

/** Runs immutable SQL migrations before the application accepts requests. */
class FlywayMigrationRunner(private val dataSource: DataSource) {
    private fun flyway(): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()

    fun migrate() {
        flyway().migrate()
    }

    /** Runtime credentials need only read access to Flyway's history. */
    fun requireCurrentSchema() {
        val flyway = flyway()
        flyway.validate()
        val migrations = flyway.info()
        require(migrations.pending().isEmpty()) { "Database schema is behind the bundled migrations" }
        require(migrations.all().none { it.state == MigrationState.FUTURE_SUCCESS || it.state == MigrationState.FUTURE_FAILED }) {
            "Database schema is ahead of the bundled migrations"
        }
    }
}
