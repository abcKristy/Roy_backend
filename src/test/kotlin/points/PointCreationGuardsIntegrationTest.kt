package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PointCreationGuardsIntegrationTest {
    @Test
    fun `parallel last slot creates one point and commits one quota denial`() = runBlocking {
        val fixture = fixture() ?: return@runBlocking
        val actor = UUID.randomUUID()
        DriverManager.getConnection(fixture.url, fixture.user, fixture.password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actor)
            repeat(2) { seedEvent(connection, actor) }
            connection.commit()
        }
        runtime(fixture).use { database ->
            val service = PointWriteService(database)
            val start = CompletableDeferred<Unit>()
            val (lat, lon) = randomPosition()
            val results = listOf(
                command(actor, lat, lon),
                command(actor, lat + 1, lon + 1),
            ).map { input ->
                async(Dispatchers.Default) {
                    start.await()
                    service.create(input, ::createdBody, ::quotaBody)
                }
            }
            start.complete(Unit)
            val outcomes = results.awaitAll()
            assertEquals(1, outcomes.count { it is CreatePointResult.Created })
            assertEquals(1, outcomes.count { it is CreatePointResult.QuotaExceeded })
        }
        DriverManager.getConnection(fixture.url, fixture.user, fixture.password).use { connection ->
            assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id = ?", actor))
            assertEquals(3, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", actor))
            assertEquals(1, count(connection,
                "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND outcome = 'SUCCESS'", actor))
            assertEquals(1, count(connection,
                "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND outcome = 'QUOTA_EXCEEDED'", actor))
            assertEquals(1, count(connection,
                "SELECT count(*) FROM users WHERE id = ? AND create_point_blocked_until > clock_timestamp()", actor))
        }
    }

    @Test
    fun `parallel nearby creates require confirmation for the second actor`() = runBlocking {
        val fixture = fixture() ?: return@runBlocking
        val actors = List(2) { UUID.randomUUID() }
        DriverManager.getConnection(fixture.url, fixture.user, fixture.password).use { connection ->
            connection.autoCommit = false
            actors.forEach { seedUser(connection, it) }
            connection.commit()
        }
        runtime(fixture).use { database ->
            val service = PointWriteService(database)
            val start = CompletableDeferred<Unit>()
            val (lat, lon) = randomPosition()
            val outcomes = actors.map { actor ->
                async(Dispatchers.Default) {
                    start.await()
                    service.create(command(actor, lat, lon), ::createdBody, ::quotaBody)
                }
            }
            start.complete(Unit)
            val results = outcomes.awaitAll()
            val created = assertIs<CreatePointResult.Created>(results.single { it is CreatePointResult.Created })
            val pending = assertIs<CreatePointResult.NearbyConfirmationRequired>(
                results.single { it is CreatePointResult.NearbyConfirmationRequired })
            assertTrue(pending.nearby.any { it.id == created.value.pointId })
        }
        DriverManager.getConnection(fixture.url, fixture.user, fixture.password).use { connection ->
            for (actor in actors) {
                val created = count(connection, "SELECT count(*) FROM points WHERE created_by_user_id = ?", actor)
                val ledger = count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", actor)
                val snapshot = count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ?", actor)
                assertEquals(created, ledger)
                assertEquals(created, snapshot)
            }
            assertEquals(1, actors.sumOf { count(connection, "SELECT count(*) FROM points WHERE created_by_user_id = ?", it) })
        }
    }

    private data class Fixture(val url: String, val user: String, val password: String)

    private fun fixture(): Fixture? {
        val url = System.getenv("TEST_DATABASE_URL") ?: return null
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t2_guards$").matches(url))
        return Fixture(url, System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required"),
            System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required"))
    }

    private fun runtime(fixture: Fixture) = HikariDatabaseBootstrap(ServerConfig(
        fixture.url, fixture.user, fixture.password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION,
    )).start()

    private fun randomPosition(): Pair<Double, Double> = ThreadLocalRandom.current().let { random ->
        random.nextDouble(-60.0, 60.0) to random.nextDouble(-150.0, 150.0)
    }

    private fun command(actor: UUID, lat: Double, lon: Double) = CreatePointCommand(
        actor, UUID.randomUUID(), PointPosition(lat, lon), PointTopicInput("football"),
        "Guard probe", PointAddressSource.MANUAL, false,
    )

    private fun createdBody(connection: Connection, value: CreatedPoint) = buildJsonObject {
        put("pointId", value.pointId.toString())
    }

    private fun quotaBody(until: java.time.Instant) = buildJsonObject { put("blockedUntil", until.toString()) }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, phone)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun seedEvent(connection: Connection, actor: UUID) {
        connection.prepareStatement(
            "INSERT INTO point_creation_events (id,creator_user_id,created_at) VALUES (?,?,clock_timestamp() - interval '1 day')"
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, actor)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun count(connection: Connection, sql: String, actor: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, actor)
        statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            rows.getInt(1)
        }
    }
}
