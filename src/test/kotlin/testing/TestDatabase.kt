package com.example.testing

import org.junit.AssumptionViolatedException
import java.net.URI

/** Gate only: the caller must provision and verify a disposable database separately. */
fun testDatabaseUrl(
    name: String = "TEST_DATABASE_URL",
    environment: Map<String, String> = System.getenv(),
    required: Boolean = java.lang.Boolean.getBoolean("integration.required"),
): String {
    val url = environment[name]?.takeIf { it.isNotBlank() }
    if (url == null) {
        val reason = "$name is required; database scenario was not executed"
        if (required) error(reason)
        throw AssumptionViolatedException(reason)
    }
    require(environment["TEST_DATABASE_DISPOSABLE"] == "true") {
        "TEST_DATABASE_DISPOSABLE=true requires explicit confirmation of disposable database ownership"
    }
    val uri = try {
        require(url.startsWith("jdbc:postgresql://"))
        URI(url.removePrefix("jdbc:"))
    } catch (_: Exception) {
        error("Test database URL is invalid")
    }
    require(uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1") &&
        uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
        !uri.path.isNullOrBlank() && uri.path != "/") {
        "Test database must use a loopback PostgreSQL endpoint without credentials or query parameters"
    }
    return url
}
