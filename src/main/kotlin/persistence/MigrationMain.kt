package com.example.persistence

/** Run once before starting a production API process. Requires a dedicated DDL account. */
fun main() {
    val environment = System.getenv()
    fun required(name: String): String = environment[name]?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable: $name")
    val url = required("DATABASE_URL")
    require(url.startsWith("jdbc:postgresql://")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
    migrationPool(url, required("MIGRATION_DATABASE_USER"), required("MIGRATION_DATABASE_PASSWORD")).use {
        FlywayMigrationRunner(it).migrate()
        FlywayMigrationRunner(it).requireCurrentSchema()
    }
}
