package com.example.config

/** Process configuration. Values are read from the JVM environment, not from Compose's .env file. */
class ServerConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String): String = environment[name]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing required environment variable: $name")

            val databaseUrl = required("DATABASE_URL")
            require(databaseUrl.startsWith("jdbc:postgresql://")) {
                "DATABASE_URL must be a PostgreSQL JDBC URL"
            }

            return ServerConfig(
                databaseUrl = databaseUrl,
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
            )
        }
    }
}
