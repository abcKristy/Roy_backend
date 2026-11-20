package com.example.persistence

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.api.configureTransport
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import java.sql.SQLException
import java.net.Socket
import java.io.BufferedReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseBootstrapIntegrationTest {
    @Test
    fun `configured timeouts are enforced in write and read transactions and reset at transaction end`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        fun settings(connection: java.sql.Connection): Pair<String, String> =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_setting('statement_timeout'), current_setting('lock_timeout')").use { rows ->
                    check(rows.next()); rows.getString(1) to rows.getString(2)
                }
            }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret",
            appEnvironment = AppEnvironment.LOCAL, databaseMaxPoolSize = 2,
            databaseStatementTimeoutMs = 500, databaseLockTimeoutMs = 100)).start().use { runtime ->
            DriverManager.getConnection(url, user, password).use { observer ->
                val defaults = settings(observer)
                val lockKey = UUID.randomUUID().mostSignificantBits
                observer.prepareStatement("SELECT pg_advisory_lock(?)").use {
                    it.setLong(1, lockKey); it.execute()
                }
                // Closing the observer releases its session lock even if an assertion fails.
                for (readOnly in listOf(false, true)) {
                    suspend fun transaction(block: (java.sql.Connection) -> Unit) {
                        if (readOnly) runtime.withReadOnlyJdbcTransaction(block)
                        else runtime.withJdbcTransaction(block)
                    }
                    for (rollback in listOf(false, true)) {
                        transaction { connection ->
                            assertEquals("500ms" to "100ms", settings(connection))
                            if (rollback) connection.rollback() else connection.commit()
                            // Inspect this exact checkout after transaction end, before returning it to the pool.
                            assertEquals(defaults, settings(connection))
                        }
                    }
                    suspend fun expectSqlState(expected: String, block: (java.sql.Connection) -> Unit) {
                        val failure = try { transaction(block); null } catch (failure: SQLException) { failure }
                        assertEquals(expected, assertNotNull(failure).sqlState)
                    }
                    expectSqlState("57014") { connection ->
                        connection.createStatement().use { it.execute("SELECT pg_sleep(2)") }
                    }
                    expectSqlState("55P03") { connection ->
                        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
                            it.setLong(1, lockKey); it.execute()
                        }
                    }
                    transaction { connection -> assertEquals("500ms" to "100ms", settings(connection)) }
                }
            }
        }
    }

    @Test
    fun `HTTP disconnect cancels active SQL without affecting another keep alive connection`() = runBlocking<Unit> {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val table = "disconnect_fixture_" + UUID.randomUUID().toString().replace("-", "")
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret",
            appEnvironment = AppEnvironment.LOCAL, databaseMaxPoolSize = 2,
            databaseStatementTimeoutMs = 20_000)).start().use { runtime ->
            DriverManager.getConnection(url, user, password).use { observer ->
                observer.createStatement().use { it.execute("CREATE TABLE $table (id integer PRIMARY KEY)") }
                val backend = CompletableDeferred<Int>()
                val finished = CompletableDeferred<Unit>()
                val healthyRequests = AtomicInteger()
                val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    configureTransport()
                    routing {
                        get("/slow") {
                            try {
                                runtime.withJdbcTransaction { connection ->
                                    connection.createStatement().use { statement ->
                                        statement.executeUpdate("INSERT INTO $table VALUES (1)")
                                        statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                            check(rows.next()); backend.complete(rows.getInt(1))
                                        }
                                        statement.execute("SELECT pg_sleep(10) /* HTTP disconnect regression */")
                                    }
                                }
                                call.respondText("completed")
                            } finally { finished.complete(Unit) }
                        }
                        get("/healthy") {
                            val id = healthyRequests.incrementAndGet() + 1
                            runtime.withJdbcTransaction { connection ->
                                connection.prepareStatement("INSERT INTO $table VALUES (?)").use {
                                    it.setInt(1, id); it.executeUpdate()
                                }
                            }
                            call.respondText("ok")
                        }
                    }
                }
                try {
                    server.start(wait = false)
                    val port = server.engine.resolvedConnectors().single().port
                    fun send(socket: Socket, path: String) {
                        socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                            "Connection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().flush()
                    }
                    fun healthyRequest(socket: Socket, reader: BufferedReader) {
                        send(socket, "/healthy")
                        assertTrue(assertNotNull(reader.readLine()).startsWith("HTTP/1.1 200 "))
                        var length: Int? = null
                        while (true) {
                            val line = assertNotNull(reader.readLine())
                            if (line.isEmpty()) break
                            if (line.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
                                length = line.substringAfter(':').trim().toInt()
                            }
                        }
                        assertEquals(2, length)
                        val body = CharArray(2)
                        var offset = 0
                        while (offset < body.size) {
                            val read = reader.read(body, offset, body.size - offset)
                            assertTrue(read > 0); offset += read
                        }
                        assertEquals("ok", String(body))
                    }
                    Socket("127.0.0.1", port).use { slow ->
                        slow.setSoLinger(true, 0)
                        send(slow, "/slow")
                        val pid = withTimeout(5_000) { backend.await() }
                        fun queryActive(): Boolean = observer.prepareStatement(
                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND state='active' AND query LIKE '%pg_sleep%')"
                        ).use { statement ->
                            statement.setInt(1, pid)
                            statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
                        }
                        withTimeout(5_000) { while (!queryActive()) delay(10) }
                        Socket("127.0.0.1", port).use { healthy ->
                            healthy.soTimeout = 5_000
                            val reader = healthy.getInputStream().bufferedReader(Charsets.US_ASCII)
                            healthyRequest(healthy, reader)
                            assertTrue(queryActive(), "The independent request must complete while the slow SQL is active")
                            slow.close()
                            withTimeout(3_000) {
                                finished.await()
                                while (queryActive()) delay(10)
                            }
                            // Reuse this exact TCP connection after the other request was cancelled.
                            healthyRequest(healthy, reader)
                        }
                    }
                    observer.createStatement().use { statement ->
                        statement.executeQuery("SELECT id FROM $table ORDER BY id").use { rows ->
                            assertTrue(rows.next()); assertEquals(2, rows.getInt(1))
                            assertTrue(rows.next()); assertEquals(3, rows.getInt(1))
                            assertTrue(!rows.next(), "The disconnected write must roll back")
                        }
                    }
                    assertTrue(runtime.isReady())
                } finally {
                    server.stop(0, 1000)
                    observer.createStatement().use { it.execute("DROP TABLE $table") }
                }
            }
        }
    }

    @Test
    fun `cancelled write transaction stops its PostgreSQL query and rolls back before pool reuse`() =
        cancellationScenario(readOnly = false)

    @Test
    fun `cancelled read transaction stops its PostgreSQL query before pool reuse`() =
        cancellationScenario(readOnly = true)

    @Test
    fun `two occupied connections cancel their server queries before subsequent pool reuse`() =
        cancellationScenario(readOnly = true, parallelism = 2)

    private fun cancellationScenario(readOnly: Boolean, parallelism: Int = 1) = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val table = "cancellation_fixture_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { observer ->
            try {
                HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret",
                    appEnvironment = AppEnvironment.LOCAL, databaseMaxPoolSize = 2,
                    databaseStatementTimeoutMs = 20_000)).start().use { runtime ->
                    observer.createStatement().use { it.execute("CREATE TABLE $table (id integer PRIMARY KEY)") }
                    supervisorScope {
                        val tasks = List(parallelism) {
                            val backend = CompletableDeferred<Int>()
                            val work = async(Dispatchers.IO) {
                                val block: (java.sql.Connection) -> Unit = { connection ->
                                    connection.createStatement().use { statement ->
                                        statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                            check(rows.next()); backend.complete(rows.getInt(1))
                                        }
                                        if (!readOnly) statement.executeUpdate("INSERT INTO $table VALUES (1)")
                                        statement.execute("SELECT pg_sleep(10) /* cancellation regression */")
                                    }
                                }
                                if (readOnly) runtime.withReadOnlyJdbcTransaction(block)
                                else runtime.withJdbcTransaction(block)
                            }
                            work to backend
                        }
                        val pids = withTimeout(5_000) { tasks.map { it.second.await() } }
                        fun queryActive(pid: Int): Boolean = observer.prepareStatement(
                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND state='active' AND query LIKE '%pg_sleep%')"
                        ).use { statement ->
                            statement.setInt(1, pid)
                            statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
                        }
                        withTimeout(5_000) { while (!pids.all(::queryActive)) delay(10) }
                        tasks.forEach { it.first.cancel() }
                        withTimeout(3_000) {
                            tasks.forEach { it.first.join() }
                            while (pids.any(::queryActive)) delay(10)
                        }
                        assertTrue(tasks.all { it.first.isCancelled })
                    }
                    // The cancelled transaction cannot leave its insert or a broken connection in the pool.
                    for (id in 2..4) {
                        runtime.withJdbcTransaction { connection ->
                            connection.prepareStatement("INSERT INTO $table VALUES (?)").use {
                                it.setInt(1, id); it.executeUpdate()
                            }
                        }
                        runtime.withReadOnlyJdbcTransaction { connection ->
                            connection.createStatement().use { statement ->
                                statement.executeQuery("SELECT id FROM $table ORDER BY id").use { rows ->
                                    for (expected in 2..id) {
                                        assertTrue(rows.next()); assertEquals(expected, rows.getInt(1))
                                    }
                                    assertTrue(!rows.next())
                                }
                            }
                        }
                    }
                    assertTrue(runtime.isReady())
                }
            } finally {
                observer.createStatement().use { it.execute("DROP TABLE IF EXISTS $table") }
            }
        }
    }

    @Test
    fun `local migration and production validation work with a short connection timeout`() = runBlocking {
        val url = com.example.testing.testDatabaseUrl("TEST_DATABASE_URL")
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_[a-z0-9]+$").matches(url)) {
            "TEST_DATABASE_URL must point to a disposable roy_m1t2_ database"
        }
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.LOCAL))
            .start().use { assertTrue(it.isReady()) }
        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-only-secret", appEnvironment = AppEnvironment.LOCAL))
            .start().use { assertTrue(it.isReady()) }

        val runtime = HikariDatabaseBootstrap(
            ServerConfig(
                url, user, password, "test-only-secret",
                appEnvironment = AppEnvironment.PRODUCTION,
                databaseConnectionTimeoutMs = 800,
            )
        ).start()
        runtime.use {
            assertTrue(it.isReady())
            assertNotNull(it.poolSnapshot())
            val settings = it.withReadOnlyJdbcTransaction { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_setting('transaction_read_only'), current_setting('transaction_isolation')")
                        .use { rows ->
                            assertTrue(rows.next())
                            rows.getString(1) to rows.getString(2)
                        }
                }
            }
            assertEquals("on" to "repeatable read", settings)

            var attempts = 0
            val result = it.withRetryingJdbcTransaction { connection ->
                attempts++
                if (attempts == 1) throw SQLException("simulated deadlock", "40P01")
                connection.acquireTransactionAdvisoryLock(AdvisoryLockKeys.pointCreation)
                attempts
            }
            assertEquals(2, result)
        }
        DriverManager.getConnection(url, user, password).use { connection ->
            try {
                connection.acquireTransactionAdvisoryLock(AdvisoryLockKeys.pointCreation)
                error("Autocommit advisory lock unexpectedly succeeded")
            } catch (_: IllegalArgumentException) {
                // Transaction-scoped locks must be called inside an explicit transaction.
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(20, rows.getInt(1))
                }
            }
        }
    }
}
