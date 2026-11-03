package com.example.testing

import org.junit.AssumptionViolatedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TestDatabaseTest {
    @Test fun `missing database is skipped rather than passed`() {
        assertFailsWith<AssumptionViolatedException> { testDatabaseUrl(environment = emptyMap(), required = false) }
    }

    @Test fun `required integration run fails without database`() {
        assertFailsWith<IllegalStateException> { testDatabaseUrl(environment = emptyMap(), required = true) }
    }

    @Test fun `database name alone does not authorize a database connection`() {
        assertFailsWith<IllegalArgumentException> {
            testDatabaseUrl(environment = mapOf("TEST_DATABASE_URL" to "jdbc:postgresql://localhost:5432/roy_test"))
        }
    }

    @Test fun `explicit disposable loopback database is accepted`() {
        val url = "jdbc:postgresql://127.0.0.1:5432/roy_test"
        assertEquals(url, testDatabaseUrl(environment = mapOf(
            "TEST_DATABASE_URL" to url, "TEST_DATABASE_DISPOSABLE" to "true")))
    }

    @Test fun `remote and malformed endpoints fail without disclosing values`() {
        listOf("jdbc:postgresql://example.invalid/roy_test", "jdbc:postgresql://user:secret@localhost/db",
            "jdbc:postgresql://localhost/db?password=secret", "jdbc:postgresql://local host/db").forEach { url ->
            val error = assertFailsWith<RuntimeException> {
                testDatabaseUrl(environment = mapOf("TEST_DATABASE_URL" to url,
                    "TEST_DATABASE_DISPOSABLE" to "true"))
            }
            assertFalse(error.toString().contains(url))
            assertFalse(error.toString().contains("secret"))
        }
    }
}
