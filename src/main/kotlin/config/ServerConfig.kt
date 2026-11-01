package com.example.config

import io.ktor.server.config.ApplicationConfig

/** Values owned by the server process. Environment binding is added in M1.T1 step 4. */
data class ServerConfig(val serviceName: String) {
    companion object {
        fun from(config: ApplicationConfig): ServerConfig = ServerConfig(
            serviceName = config.propertyOrNull("server.name")?.getString() ?: "roy-backend",
        )
    }
}
