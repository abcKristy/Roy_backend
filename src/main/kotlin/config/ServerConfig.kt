package com.example.config

import java.time.Duration
import java.net.URI

enum class AppEnvironment { LOCAL, PRODUCTION }

/** Process configuration. Values are read from the JVM environment, not from Compose's .env file. */
class ServerConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val jwtTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(30),
    val appEnvironment: AppEnvironment = AppEnvironment.LOCAL,
    val draftPrivacyEnabled: Boolean = false,
    val publicBaseUrl: URI? = null,
    val serverHost: String = "127.0.0.1",
    val migrationUser: String? = null,
    val migrationPassword: String? = null,
    val databaseMaxPoolSize: Int = 5,
    val databaseConnectionTimeoutMs: Long = 5_000,
    val databaseStatementTimeoutMs: Long = 5_000,
    val databaseLockTimeoutMs: Long = 1_000,
    val pushTokenEncryptionKeyBase64: String? = null,
) {
    init {
        require((migrationUser == null) == (migrationPassword == null)) { "Migration credentials must be provided together" }
        require(databaseMaxPoolSize in 1..100) { "DATABASE_MAX_POOL_SIZE must be between 1 and 100" }
        require(databaseConnectionTimeoutMs in 500..60_000) { "DATABASE_CONNECTION_TIMEOUT_MS must be between 500 and 60000" }
        require(databaseStatementTimeoutMs in 100..60_000) { "DATABASE_STATEMENT_TIMEOUT_MS must be between 100 and 60000" }
        require(databaseLockTimeoutMs in 1 until databaseStatementTimeoutMs) { "DATABASE_LOCK_TIMEOUT_MS must be below statement timeout" }
        require(jwtTtl.nano == 0 && jwtTtl.seconds in 60..30L * 24 * 60 * 60) {
            "JWT_TTL_SECONDS must be between 60 and 2592000"
        }
        require(refreshTtl.nano == 0 && refreshTtl.seconds in 60..30L * 24 * 60 * 60) {
            "REFRESH_TTL_SECONDS must be between 60 and 2592000"
        }
        require(!draftPrivacyEnabled || appEnvironment == AppEnvironment.LOCAL) {
            "Draft privacy policy cannot be enabled in production"
        }
        if (draftPrivacyEnabled) require(serverHost.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
            "Draft privacy policy requires a loopback SERVER_HOST"
        }
        if (draftPrivacyEnabled) requireNotNull(publicBaseUrl) {
            "PUBLIC_BASE_URL is required for draft privacy policy"
        }
        publicBaseUrl?.let { url ->
            require(url.scheme in setOf("http", "https") && !url.host.isNullOrBlank() &&
                url.userInfo == null && url.query == null && url.fragment == null &&
                (url.path.isNullOrEmpty() || url.path == "/") && url.port != 0
            ) { "PUBLIC_BASE_URL must be an HTTP(S) origin without a path, query or fragment" }
            if (draftPrivacyEnabled) require(url.host.orEmpty().lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")) {
                "Draft privacy policy requires a loopback PUBLIC_BASE_URL"
            }
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
            } ?: 15L * 60
            val refreshTtlSeconds = environment["REFRESH_TTL_SECONDS"]?.let { raw ->
                raw.toLongOrNull() ?: throw IllegalArgumentException("REFRESH_TTL_SECONDS must be an integer")
            } ?: 30L * 24 * 60 * 60
            val appEnvironment = when (environment["APP_ENV"] ?: "production") {
                "local" -> AppEnvironment.LOCAL
                "production" -> AppEnvironment.PRODUCTION
                else -> throw IllegalArgumentException("APP_ENV must be local or production")
            }
            val draftPrivacyEnabled = when (environment["AUTH_DRAFT_POLICY_ENABLED"] ?: "false") {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("AUTH_DRAFT_POLICY_ENABLED must be true or false")
            }
            if (draftPrivacyEnabled) require(environment["APP_ENV"] == "local") {
                "APP_ENV=local must be set explicitly for draft privacy policy"
            }
            val publicBaseUrl = environment["PUBLIC_BASE_URL"]?.takeIf { it.isNotBlank() }?.let { raw ->
                try { URI(raw) } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("PUBLIC_BASE_URL is invalid")
                }
            }

            fun intSetting(name: String, default: Int): Int = environment[name]?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer")
            } ?: default
            fun longSetting(name: String, default: Long): Long = environment[name]?.let {
                it.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer")
            } ?: default
            return ServerConfig(
                databaseUrl = databaseUrl,
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                jwtTtl = Duration.ofSeconds(ttlSeconds),
                refreshTtl = Duration.ofSeconds(refreshTtlSeconds),
                appEnvironment = appEnvironment,
                draftPrivacyEnabled = draftPrivacyEnabled,
                publicBaseUrl = publicBaseUrl,
                serverHost = environment["SERVER_HOST"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                migrationUser = environment["MIGRATION_DATABASE_USER"]?.takeIf { it.isNotBlank() },
                migrationPassword = environment["MIGRATION_DATABASE_PASSWORD"]?.takeIf { it.isNotBlank() },
                databaseMaxPoolSize = intSetting("DATABASE_MAX_POOL_SIZE", 5),
                databaseConnectionTimeoutMs = longSetting("DATABASE_CONNECTION_TIMEOUT_MS", 5_000),
                databaseStatementTimeoutMs = longSetting("DATABASE_STATEMENT_TIMEOUT_MS", 5_000),
                databaseLockTimeoutMs = longSetting("DATABASE_LOCK_TIMEOUT_MS", 1_000),
                pushTokenEncryptionKeyBase64 = environment["PUSH_TOKEN_ENCRYPTION_KEY_BASE64"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
