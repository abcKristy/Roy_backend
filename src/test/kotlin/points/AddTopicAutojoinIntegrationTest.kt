package com.example.points

import com.example.api.PointWriteSnapshot
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddTopicAutojoinIntegrationTest {
    @Test
    fun `concurrent canonical duplicate creates one topic and no loser membership`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t3_autojoin$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actors = List(2) { UUID.randomUUID() }
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            actors.forEach { seedUser(connection, it) }
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val point = assertIs<CreatePointResult.Created>(service.create(
                CreatePointCommand(actors.first(), UUID.randomUUID(),
                    PointPosition(ThreadLocalRandom.current().nextDouble(40.0, 60.0),
                        ThreadLocalRandom.current().nextDouble(20.0, 40.0)),
                    PointTopicInput("football"), "Duplicate fixture", PointAddressSource.MANUAL, false),
                PointWriteSnapshot::create, ::quotaBody,
            ))
            val commands = listOf(
                AddTopicCommand(actors[0], UUID.randomUUID(), point.value.pointId, PointTopicInput("other", " Evening Chess ")),
                AddTopicCommand(actors[1], UUID.randomUUID(), point.value.pointId, PointTopicInput("other", "evening chess")),
            )
            val start = CompletableDeferred<Unit>()
            val pending = commands.map { command ->
                async(Dispatchers.Default) {
                    start.await()
                    service.addTopic(command, PointWriteSnapshot::addTopic)
                }
            }
            start.complete(Unit)
            val results = pending.awaitAll()
            val winner = results.indexOfFirst { it is AddTopicResult.Created }
            assertTrue(winner >= 0)
            assertEquals(1, results.count { it is AddTopicResult.Created })
            assertEquals(1, results.count { it == AddTopicResult.DuplicateTopic })

            DriverManager.getConnection(url, user, password).use { connection ->
                connection.prepareStatement("SELECT preset_key, custom_label FROM point_topics WHERE id = ?").use { statement ->
                    statement.setObject(1, point.value.topicId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("football", rows.getString(1))
                        assertEquals(null, rows.getString(2))
                        assertTrue(!rows.next())
                    }
                }
                val winningTopic = assertIs<AddTopicResult.Created>(results[winner]).value.topicId
                connection.prepareStatement("SELECT preset_key, custom_label FROM point_topics WHERE id = ?").use { statement ->
                    statement.setObject(1, winningTopic)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("other", rows.getString(1))
                        assertEquals(commands[winner].topic.customLabel?.trim(), rows.getString(2))
                        assertTrue(!rows.next())
                    }
                }
                assertEquals(1, count(connection,
                    "SELECT count(*) FROM point_topics WHERE point_id = ? AND preset_key = 'other' AND lower(btrim(custom_label)) = 'evening chess'",
                    point.value.pointId))
                assertEquals(1, count(connection,
                    """SELECT count(*) FROM chats AS c JOIN point_topics AS t ON t.id = c.point_topic_id
                       WHERE t.point_id = ? AND t.preset_key = 'other'""", point.value.pointId))
                assertEquals(1, count(connection,
                    """SELECT count(*) FROM chat_memberships AS m JOIN chats AS c ON c.id = m.chat_id
                       JOIN point_topics AS t ON t.id = c.point_topic_id
                       WHERE t.point_id = ? AND t.preset_key = 'other' AND m.left_at IS NULL""", point.value.pointId))
                assertEquals(1, count(connection,
                    "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND operation_type = 'add_topic'",
                    actors[winner]))
                assertEquals(0, count(connection,
                    "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND operation_type = 'add_topic'",
                    actors[1 - winner]))
            }
        }
    }

    @Test
    fun `database topic unique violation maps only its own constraint to duplicate`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t3_autojoin$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actor)
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val point = assertIs<CreatePointResult.Created>(service.create(
                CreatePointCommand(actor, UUID.randomUUID(),
                    PointPosition(ThreadLocalRandom.current().nextDouble(-60.0, -40.0),
                        ThreadLocalRandom.current().nextDouble(80.0, 100.0)),
                    PointTopicInput("football"), "Unique fixture", PointAddressSource.MANUAL, false),
                PointWriteSnapshot::create, ::quotaBody,
            ))
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS test_topic_unique_error ON point_topics")
                    statement.execute("DROP FUNCTION IF EXISTS test_raise_topic_unique_error()")
                    statement.execute("""CREATE FUNCTION test_raise_topic_unique_error() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.custom_label = 'force_unique' THEN
                                RAISE EXCEPTION 'forced unique conflict' USING ERRCODE = '23505',
                                    CONSTRAINT = 'point_topics_normalized_unique';
                            ELSIF NEW.custom_label = 'force_other' THEN
                                RAISE EXCEPTION 'unrelated unique conflict' USING ERRCODE = '23505',
                                    CONSTRAINT = 'other_unique_constraint';
                            END IF;
                            RETURN NEW;
                        END
                        $$""")
                    statement.execute("""CREATE TRIGGER test_topic_unique_error BEFORE INSERT ON point_topics
                        FOR EACH ROW EXECUTE FUNCTION test_raise_topic_unique_error()""")
                }
            }
            try {
                val duplicate = service.addTopic(AddTopicCommand(actor, UUID.randomUUID(), point.value.pointId,
                    PointTopicInput("other", "force_unique")), PointWriteSnapshot::addTopic)
                assertEquals(AddTopicResult.DuplicateTopic, duplicate)
                val unrelated = assertFailsWith<PSQLException> {
                    service.addTopic(AddTopicCommand(actor, UUID.randomUUID(), point.value.pointId,
                        PointTopicInput("other", "force_other")), PointWriteSnapshot::addTopic)
                }
                assertEquals("23505", unrelated.sqlState)
                assertEquals("other_unique_constraint", unrelated.serverErrorMessage?.constraint)
                DriverManager.getConnection(url, user, password).use { connection ->
                    assertEquals(1, count(connection, "SELECT count(*) FROM point_topics WHERE point_id = ?", point.value.pointId))
                    assertEquals(0, count(connection,
                        "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND operation_type = 'add_topic'", actor))
                }
            } finally {
                DriverManager.getConnection(url, user, password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP TRIGGER IF EXISTS test_topic_unique_error ON point_topics")
                        statement.execute("DROP FUNCTION IF EXISTS test_raise_topic_unique_error()")
                    }
                }
            }
        }
    }

    @Test
    fun `add topic commits chat membership and replay together or rolls all back`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m2t3_autojoin$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actorId = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actorId)
            connection.commit()
        }

        HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start().use { runtime ->
            val service = PointWriteService(runtime)
            val latitude = ThreadLocalRandom.current().nextDouble(40.0, 60.0)
            val longitude = ThreadLocalRandom.current().nextDouble(20.0, 40.0)
            val point = assertIs<CreatePointResult.Created>(service.create(
                CreatePointCommand(actorId, UUID.randomUUID(), PointPosition(latitude, longitude),
                    PointTopicInput("football"), "Autojoin fixture", PointAddressSource.MANUAL, false),
                PointWriteSnapshot::create, ::quotaBody,
            ))
            val command = AddTopicCommand(actorId, UUID.randomUUID(), point.value.pointId, PointTopicInput("tennis"))
            val added = assertIs<AddTopicResult.Created>(service.addTopic(command, PointWriteSnapshot::addTopic))
            val body = added.responseSnapshot.getValue("body").jsonObject
            assertEquals(added.value.topicId.toString(), body.getValue("topic").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(added.value.chatId.toString(), body.getValue("chat").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(actorId.toString(), body.getValue("membership").jsonObject.getValue("userId").jsonPrimitive.content)

            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(2, count(connection, "SELECT count(*) FROM point_topics WHERE point_id = ?", point.value.pointId))
                assertEquals(1, count(connection, "SELECT count(*) FROM chats WHERE id = ? AND participant_count = 1", added.value.chatId))
                assertEquals(1, count(connection, "SELECT count(*) FROM chat_memberships WHERE chat_id = ? AND user_id IS NOT NULL AND left_at IS NULL", added.value.chatId))
                assertEquals(1, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND operation_type = 'add_topic'", actorId))
            }
            assertEquals(added.responseSnapshot,
                assertIs<AddTopicResult.Replay>(service.addTopic(command, PointWriteSnapshot::addTopic)).responseSnapshot)

            val failing = command.copy(idempotencyKey = UUID.randomUUID(), topic = PointTopicInput("other", "Rollback topic"))
            assertFailsWith<IllegalStateException> {
                service.addTopic(failing, { _, _ -> error("snapshot failed") })
            }
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(2, count(connection, "SELECT count(*) FROM point_topics WHERE point_id = ?", point.value.pointId))
                assertEquals(1, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ? AND operation_type = 'add_topic'", actorId))
                assertTrue(connection.prepareStatement(
                    "SELECT NOT EXISTS (SELECT 1 FROM point_topics WHERE point_id = ? AND custom_label = 'Rollback topic')"
                ).use { statement ->
                    statement.setObject(1, point.value.pointId)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                })
            }
        }
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
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())").use { statement ->
            statement.setObject(1, id)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun count(connection: Connection, sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            rows.getInt(1)
        }
    }
}
