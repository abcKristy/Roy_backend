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
    val jwtTtl: Duration = Duration.ofDays(30),
    val appEnvironment: AppEnvironment = AppEnvironment.LOCAL,
    val draftPrivacyEnabled: Boolean = false,
    val publicBaseUrl: URI? = null,
    val serverHost: String = "127.0.0.1",
) {
    init {
        require(jwtTtl.nano == 0 && jwtTtl.seconds in 60..30L * 24 * 60 * 60) {
            "JWT_TTL_SECONDS must be between 60 and 2592000"
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
            } ?: 30L * 24 * 60 * 60
            val appEnvironment = when (environment["APP_ENV"] ?: "local") {
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

            return ServerConfig(
                databaseUrl = databaseUrl,
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                jwtTtl = Duration.ofSeconds(ttlSeconds),
                appEnvironment = appEnvironment,
                draftPrivacyEnabled = draftPrivacyEnabled,
                publicBaseUrl = publicBaseUrl,
                serverHost = environment["SERVER_HOST"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            )
        }
    }
}
