package com.example.points

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PointWriteServiceIntegrationTest {
    @Test
    fun `create rolls back the complete graph when response projection fails`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step4write$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val actor = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, actor)
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret",
            appEnvironment = AppEnvironment.PRODUCTION)).start()
        runtime.use {
            val command = CreatePointCommand(actor, UUID.randomUUID(),
                PointPosition(ThreadLocalRandom.current().nextDouble(55.0, 56.0),
                    ThreadLocalRandom.current().nextDouble(37.0, 38.0)),
                PointTopicInput("football"), "Atomic create probe", PointAddressSource.MANUAL, false)
            val service = PointWriteService(it)
            assertFailsWith<IllegalStateException> {
                service.create(command, { _, _ -> error("response projection failed") }, ::quotaBody)
            }
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE created_by_user_id = ?", actor))
                assertEquals(0, count(connection, "SELECT count(*) FROM chat_memberships WHERE user_id = ?", actor))
                assertEquals(0, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", actor))
                assertEquals(0, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ?", actor))
            }

            val created = assertIs<CreatePointResult.Created>(service.create(command, ::createdPointBody, ::quotaBody))
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(1, count(connection, "SELECT count(*) FROM points WHERE id = ?", created.value.pointId))
                assertEquals(1, count(connection, "SELECT count(*) FROM point_topics WHERE id = ?", created.value.topicId))
                assertEquals(1, count(connection, "SELECT count(*) FROM chats WHERE id = ? AND participant_count = 1", created.value.chatId))
                assertEquals(1, count(connection, "SELECT count(*) FROM chat_memberships WHERE user_id = ? AND left_at IS NULL", actor))
                assertEquals(1, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", actor))
                assertEquals(1, count(connection, "SELECT count(*) FROM operation_idempotency WHERE actor_user_id = ?", actor))
            }
        }
    }

    @Test
    fun `quota denials persist replay and do not extend block`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step4write$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val weekActor = UUID.randomUUID()
        val monthActor = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, weekActor)
            seedUser(connection, monthActor)
            seedQuotaEvents(connection, weekActor, recent = 3, older = 0)
            seedQuotaEvents(connection, monthActor, recent = 2, older = 8)
            connection.commit()
        }
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start()
        runtime.use {
            val service = PointWriteService(it)
            for (actor in listOf(weekActor, monthActor)) {
                val key = UUID.randomUUID()
                val command = CreatePointCommand(actor, key, PointPosition(55.4, 37.4),
                    PointTopicInput("football"), "Quota probe", PointAddressSource.MANUAL, false)
                val denied = assertIs<CreatePointResult.QuotaExceeded>(service.create(command, ::createdPointBody, ::quotaBody))
                assertEquals(409, denied.responseSnapshot["status"]?.toString()?.toInt())
                assertEquals(denied.responseSnapshot, assertIs<CreatePointResult.Replay>(
                    service.create(command.copy(confirmNearby = true), ::createdPointBody, ::quotaBody)
                ).responseSnapshot)
                assertEquals(denied.blockedUntil, assertIs<CreatePointResult.QuotaBlocked>(
                    service.create(command.copy(idempotencyKey = UUID.randomUUID()), ::createdPointBody, ::quotaBody)
                ).blockedUntil)
                DriverManager.getConnection(url, user, password).use { connection ->
                    connection.prepareStatement(
                        """SELECT u.create_point_blocked_until, i.outcome, i.response_snapshot,
                                  (SELECT count(*) FROM operation_idempotency
                                   WHERE actor_user_id = u.id AND operation_type = 'create_point') AS key_count
                           FROM users AS u JOIN operation_idempotency AS i ON i.actor_user_id = u.id
                           WHERE u.id = ? AND i.idempotency_key = ?"""
                    ).use { statement ->
                        statement.setObject(1, actor)
                        statement.setString(2, key.toString())
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(denied.blockedUntil, rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant())
                            assertEquals("QUOTA_EXCEEDED", rows.getString(2))
                            assertEquals(denied.responseSnapshot,
                                kotlinx.serialization.json.Json.parseToJsonElement(rows.getString(3)))
                            assertEquals(1, rows.getInt(4))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `create add join and leave keep point graph and booking consistent`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL") ?: return@runBlocking
        require(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step4write$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
        val firstUser = UUID.randomUUID()
        val secondUser = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            seedUser(connection, firstUser)
            seedUser(connection, secondUser)
            connection.commit()
        }
        val latitude = ThreadLocalRandom.current().nextDouble(55.0, 56.0)
        val longitude = ThreadLocalRandom.current().nextDouble(37.0, 38.0)
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, "test-secret", appEnvironment = AppEnvironment.PRODUCTION)).start()
        runtime.use {
            val service = PointWriteService(it)
            val firstCommand = CreatePointCommand(firstUser, UUID.randomUUID(), PointPosition(latitude, longitude),
                PointTopicInput("football"), "😀".repeat(300), PointAddressSource.MANUAL, false)
            val first = assertIs<CreatePointResult.Created>(service.create(firstCommand, ::createdPointBody, ::quotaBody))
            assertEquals(201, first.responseSnapshot["status"]?.toString()?.toInt())
            assertEquals(first.responseSnapshot, assertIs<CreatePointResult.Replay>(
                service.create(firstCommand.copy(confirmNearby = true), ::createdPointBody, ::quotaBody)
            ).responseSnapshot)

            val secondCommand = CreatePointCommand(secondUser, UUID.randomUUID(), PointPosition(latitude, longitude + 0.0002),
                PointTopicInput("tennis"), "Nearby", PointAddressSource.MANUAL, false)
            val nearby = assertIs<CreatePointResult.NearbyConfirmationRequired>(
                service.create(secondCommand, ::createdPointBody, ::quotaBody)
            )
            assertTrue(nearby.nearby.any { point -> point.id == first.value.pointId })
            val firstPreview = nearby.nearby.first { point -> point.id == first.value.pointId }
            assertEquals(latitude, firstPreview.lat)
            assertEquals(longitude, firstPreview.lon)
            assertIs<CreatePointResult.Created>(service.create(secondCommand.copy(confirmNearby = true), ::createdPointBody, ::quotaBody))

            val added = assertIs<AddTopicResult.Created>(service.addTopic(
                AddTopicCommand(firstUser, UUID.randomUUID(), first.value.pointId,
                    PointTopicInput("other", "😀".repeat(40))),
                ::createdTopicBody,
            ))
            assertIs<JoinPointChatResult.Joined>(service.join(secondUser, first.value.chatId))
            assertIs<JoinPointChatResult.AlreadyMember>(service.join(secondUser, first.value.chatId))

            val proposal = UUID.randomUUID()
            val booking = UUID.randomUUID()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.autoCommit = false
                connection.prepareStatement(
                    """INSERT INTO meeting_proposals (id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                       VALUES (?,?,?,clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 day 1 hour',
                               clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, proposal); statement.setObject(2, first.value.chatId)
                    statement.setObject(3, firstUser); statement.executeUpdate()
                }
                for (voter in listOf(firstUser, secondUser)) connection.prepareStatement(
                    "INSERT INTO votes (proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, proposal); statement.setObject(2, voter); statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO bookings (id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())"
                ).use { statement ->
                    statement.setObject(1, booking); statement.setObject(2, proposal)
                    statement.setObject(3, firstUser); statement.executeUpdate()
                }
                val digest = UUID.randomUUID().toString().replace("-", "").repeat(2)
                connection.prepareStatement(
                    """INSERT INTO device_registrations
                       (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at)
                       VALUES (?,?,'ANDROID_RUSTORE','ciphertext',?,clock_timestamp(),clock_timestamp())"""
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID()); statement.setObject(2, firstUser)
                    statement.setString(3, digest); statement.executeUpdate()
                }
                connection.commit()
            }

            val secondLeave = assertIs<LeavePointChatResult.Left>(service.leave(secondUser, first.value.chatId))
            assertEquals(proposal, secondLeave.changedProposals.single().id)
            assertEquals("cancelled", secondLeave.changedProposals.single().booking?.status)
            assertEquals(booking, secondLeave.bookingChanges.single().bookingId)
            assertEquals("meeting_booking_cancelled", secondLeave.systemMessages.first().draft.kind.databaseValue)
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(1, count(connection, "SELECT participant_count FROM chats WHERE id = ?", first.value.chatId))
                assertEquals(4, count(connection, "SELECT last_message_seq FROM chats WHERE id = ?", first.value.chatId))
                assertEquals(1, count(connection, "SELECT count(*) FROM bookings WHERE id = ? AND status='CANCELLED'", booking))
                assertEquals(1, count(connection, "SELECT count(*) FROM notification_events WHERE booking_id = ?", booking))
                assertEquals(1, count(connection,
                    "SELECT count(*) FROM push_delivery_jobs AS j JOIN notification_events AS e ON e.id=j.notification_event_id WHERE e.booking_id=?",
                    booking))
            }
            assertIs<LeavePointChatResult.Left>(service.leave(firstUser, first.value.chatId))
            val lastLeave = assertIs<LeavePointChatResult.Left>(service.leave(firstUser, added.value.chatId))
            assertTrue(lastLeave.pointDeleted)
            assertEquals(setOf(firstUser, secondUser), lastLeave.deletedRecipients.toSet())
            DriverManager.getConnection(url, user, password).use { connection ->
                assertEquals(0, count(connection, "SELECT count(*) FROM points WHERE id = ?", first.value.pointId))
                assertEquals(1, count(connection, "SELECT count(*) FROM point_creation_events WHERE creator_user_id = ?", firstUser))
            }
        }
    }

    private fun createdPointBody(connection: Connection, value: CreatedPoint) = buildJsonObject {
        put("pointId", value.pointId.toString())
        put("chatId", value.chatId.toString())
    }

    private fun createdTopicBody(connection: Connection, value: CreatedTopic) = buildJsonObject {
        put("topicId", value.topicId.toString())
        put("chatId", value.chatId.toString())
    }

    private fun quotaBody(until: java.time.Instant) = buildJsonObject { put("blockedUntil", until.toString()) }

    private fun seedUser(connection: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        connection.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test-hash','Tester',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { statement ->
            statement.setObject(1, id); statement.setString(2, phone); statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())").use { statement ->
            statement.setObject(1, id); statement.executeUpdate()
        }
    }

    private fun seedQuotaEvents(connection: Connection, actor: UUID, recent: Int, older: Int) {
        repeat(recent) {
            connection.prepareStatement(
                """INSERT INTO point_creation_events (id,creator_user_id,created_at)
                   VALUES (?,?,clock_timestamp() - interval '1 day')"""
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, actor)
                statement.executeUpdate()
            }
        }
        repeat(older) {
            connection.prepareStatement(
                """INSERT INTO point_creation_events (id,creator_user_id,created_at)
                   VALUES (?,?,clock_timestamp() - interval '8 days')"""
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, actor)
                statement.executeUpdate()
            }
        }
    }

    private fun count(connection: Connection, sql: String, id: UUID): Int = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, id)
        statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    }
}
