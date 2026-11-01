package com.example.config

import java.time.Duration

/** Process configuration. Values are read from the JVM environment, not from Compose's .env file. */
class ServerConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val jwtTtl: Duration = Duration.ofDays(30),
) {
    init {
        require(jwtTtl.nano == 0 && jwtTtl.seconds in 60..30L * 24 * 60 * 60) {
            "JWT_TTL_SECONDS must be between 60 and 2592000"
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String): String = environment[name]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing required environment variable: $name")

            val databaseUrl = required("DATABASE_URL")
            require(databaseUrl.startsWith("jdbc:postgresql://")) {
                "DATABASE_URL must be a PostgreSQL JDBC URL"
            }
            val ttlSeconds = environment["JWT_TTL_SECONDS"]?.let { raw ->
                raw.toLongOrNull() ?: throw IllegalArgumentException("JWT_TTL_SECONDS must be an integer")
            } ?: 30L * 24 * 60 * 60

            return ServerConfig(
                databaseUrl = databaseUrl,
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                jwtTtl = Duration.ofSeconds(ttlSeconds),
            )
        }
    }
}
