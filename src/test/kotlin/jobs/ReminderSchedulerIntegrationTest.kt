package com.example.jobs

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.notifications.NotificationService
import com.example.notifications.NotificationSettingsChange
import com.example.notifications.SettingsUpdateResult
import com.example.persistence.DatabaseRuntime
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReminderSchedulerIntegrationTest {
    @Test
    fun `restarted limited poll advances past already notified booking`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val early = fixture.booking(secondsUntilStart = 3510)
            val later = fixture.booking(secondsUntilStart = 3540)

            assertEquals(2, fixture.scheduler().tick(limit = 1).eventsCreated)
            val firstEvents = fixture.reminders(early)
            assertEquals(setOf(fixture.owner, fixture.peer), firstEvents.keys)
            assertEquals(emptyMap(), fixture.reminders(later))

            assertEquals(2, fixture.scheduler().tick(limit = 1).eventsCreated)
            assertEquals(firstEvents, fixture.reminders(early))
            assertEquals(setOf(fixture.owner, fixture.peer), fixture.reminders(later).keys)
            val idle = fixture.scheduler().tick(limit = 1)
            assertEquals(0, idle.bookingsVisited)
            assertEquals(0, idle.eventsCreated)
        }
    }

    @Test
    fun `locked point does not delay reminders for another point`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val blocked = fixture.booking(secondsUntilStart = 3510)
            val available = fixture.booking(secondsUntilStart = 3540)
            val scheduler = fixture.scheduler()

            fixture.withPointLocked(blocked) {
                assertEquals(ReminderTick(2, 2, 0), scheduler.tick())
                assertEquals(emptyMap(), fixture.reminders(blocked))
                assertEquals(setOf(fixture.owner, fixture.peer), fixture.reminders(available).keys)
            }
            assertEquals(ReminderTick(1, 2, 0), scheduler.tick())
            assertEquals(setOf(fixture.owner, fixture.peer), fixture.reminders(blocked).keys)
        }
    }

    @Test
    fun `two claims selected before either commits create one reminder per active member`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val booking = fixture.booking(secondsUntilStart = 3540)
            val synchronized = AfterDiscovery(fixture.database)
            val first = async(Dispatchers.IO) { fixture.scheduler(synchronized).tick() }
            val second = async(Dispatchers.IO) { fixture.scheduler(synchronized).tick() }

            val results = listOf(first.await(), second.await())
            assertEquals(listOf(1, 1), results.map(ReminderTick::bookingsVisited))
            assertEquals(listOf(0, 2), results.map(ReminderTick::eventsCreated).sorted())
            val events = fixture.reminders(booking)
            assertEquals(setOf(fixture.owner, fixture.peer), events.keys)
            assertEquals(0, fixture.scheduler().tick().eventsCreated)
            assertEquals(events, fixture.reminders(booking))
        }
    }

    @Test
    fun `database discovery shares the exact UTC window boundaries`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val due = fixture.booking(secondsUntilStart = 3600)
            val beforeDue = fixture.booking(secondsUntilStart = 3601)
            val missed = fixture.booking(secondsUntilStart = 3480)

            val result = fixture.scheduler().tick()
            assertEquals(ReminderTick(1, 2, 1), result)
            assertEquals(setOf(fixture.owner, fixture.peer), fixture.reminders(due).keys)
            assertEquals(emptyMap(), fixture.reminders(beforeDue))
            assertEquals(emptyMap(), fixture.reminders(missed))
        }
    }

    @Test
    fun `reminder channel keeps in app history and fans out only to active opted in devices`() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val booking = fixture.booking(secondsUntilStart = 3540)
            fixture.preferences(fixture.owner, reminders = false, personal = true)
            fixture.preferences(fixture.peer, reminders = true, personal = false)
            fixture.device(fixture.owner)
            val peerInstallation = fixture.device(fixture.peer)
            fixture.device(fixture.peer, revoked = true)

            assertEquals(2, fixture.scheduler().tick().eventsCreated)
            val ids = fixture.reminders(booking)
            assertEquals(ids.getValue(fixture.owner), fixture.notifications.list(fixture.owner, null, 20).items.single().id)
            assertEquals(ids.getValue(fixture.peer), fixture.notifications.list(fixture.peer, null, 20).items.single().id)
            assertEquals(mapOf(fixture.owner to false, fixture.peer to true), fixture.pushFlags(booking))
            assertEquals(setOf(peerInstallation), fixture.jobInstallations(booking))

            fixture.preferences(fixture.owner, reminders = true, personal = true)
            assertEquals(0, fixture.scheduler().tick().eventsCreated)
            assertEquals(ids, fixture.reminders(booking))
            assertEquals(mapOf(fixture.owner to false, fixture.peer to true), fixture.pushFlags(booking))
            assertEquals(setOf(peerInstallation), fixture.jobInstallations(booking))
        }
    }

    private class AfterDiscovery(private val database: DatabaseRuntime) : DatabaseRuntime by database {
        private val reads = AtomicInteger()
        private val bothSelected = CyclicBarrier(2)

        override suspend fun <T> withReadOnlyJdbcTransaction(block: (Connection) -> T): T {
            val result = database.withReadOnlyJdbcTransaction(block)
            if (reads.incrementAndGet() <= 2) bothSelected.await(15, TimeUnit.SECONDS)
            return result
        }
    }

    private class Fixture : AutoCloseable {
        private val now = Instant.parse("2030-06-15T12:00:00Z")
        private val url = testDatabaseUrl().also { require(it.endsWith("/roy_b55_reminders")) }
        val database = HikariDatabaseBootstrap(ServerConfig(
            url, checkNotNull(System.getenv("DATABASE_USER")), checkNotNull(System.getenv("DATABASE_PASSWORD")),
            "local-reminder-fixture-jwt-secret-of-sufficient-length", appEnvironment = AppEnvironment.LOCAL,
            databaseLockTimeoutMs = 5_000, databaseStatementTimeoutMs = 15_000,
        )).start()
        val notifications = NotificationService(database, "local-reminder-fixture-cursor-secret-of-sufficient-length")
        val owner = UUID.randomUUID()
        val peer = UUID.randomUUID()
        private val former = UUID.randomUUID()
        private val outsider = UUID.randomUUID()
        private val users = listOf(owner, peer, former, outsider)
        private val points = mutableListOf<UUID>()

        suspend fun booking(secondsUntilStart: Long): UUID = database.withJdbcTransaction { connection ->
            val point = UUID.randomUUID()
            val topic = UUID.randomUUID()
            val chat = UUID.randomUUID()
            val proposal = UUID.randomUUID()
            val booking = UUID.randomUUID()
            val start = now.plusSeconds(secondsUntilStart)
            if (points.isEmpty()) {
                for (user in users) {
                    val phone = "+7" + user.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                    connection.execute(
                        """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,
                           accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                           VALUES (?,?,'synthetic-hash','Reminder fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""",
                        user, phone,
                    )
                    connection.execute(
                        "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", user,
                    )
                }
            }
            connection.execute(
                """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                   VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Reminder fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""",
                point, owner,
            )
            connection.execute("INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
            connection.execute("INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
            for (member in listOf(owner, peer, former)) {
                connection.execute(
                    "INSERT INTO chat_memberships(chat_id,user_id,joined_at,left_at) VALUES (?,?,clock_timestamp(),?)",
                    chat, member, if (member == former) now.atOffset(ZoneOffset.UTC) else null,
                )
            }
            connection.execute(
                """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                   VALUES (?,?,?,?,?,clock_timestamp(),clock_timestamp())""",
                proposal, chat, owner, start.atOffset(ZoneOffset.UTC), start.plusSeconds(3600).atOffset(ZoneOffset.UTC),
            )
            connection.execute(
                "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,?)",
                booking, proposal, owner, start.minusSeconds(3600).atOffset(ZoneOffset.UTC),
            )
            points += point
            booking
        }

        fun scheduler(runtime: DatabaseRuntime = database) = ReminderScheduler(runtime, clock = Clock.fixed(now, ZoneOffset.UTC))

        suspend fun withPointLocked(booking: UUID, block: suspend () -> Unit) {
            DriverManager.getConnection(url, System.getenv("DATABASE_USER"), System.getenv("DATABASE_PASSWORD")).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """SELECT point.id FROM bookings AS b
                           JOIN meeting_proposals AS p ON p.id=b.proposal_id
                           JOIN chats AS c ON c.id=p.chat_id JOIN point_topics AS pt ON pt.id=c.point_topic_id
                           JOIN points AS point ON point.id=pt.point_id WHERE b.id=? FOR UPDATE OF point"""
                    ).use { statement ->
                        statement.setObject(1, booking)
                        statement.executeQuery().use { rows -> check(rows.next()) }
                    }
                    block()
                } finally {
                    connection.rollback()
                }
            }
        }

        suspend fun reminders(booking: UUID): Map<UUID, UUID> = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                """SELECT recipient_user_id,id FROM notification_events
                   WHERE booking_id=? AND event_type='reminder_1h'""",
            ).use { statement ->
                statement.setObject(1, booking)
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            val prior = put(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java))
                            check(prior == null) { "Duplicate logical reminder" }
                        }
                    }
                }
            }
        }

        suspend fun preferences(user: UUID, reminders: Boolean, personal: Boolean) {
            val revision = checkNotNull(notifications.settings(user)).revision
            assertIs<SettingsUpdateResult.Updated>(
                notifications.updateSettings(user, NotificationSettingsChange(reminders, personal, false, false, revision)),
            )
        }

        suspend fun device(user: UUID, revoked: Boolean = false): UUID = database.withJdbcTransaction { connection ->
            val installation = UUID.randomUUID()
            connection.execute(
                """INSERT INTO device_registrations(installation_id,user_id,platform,token_ciphertext,token_digest,
                   created_at,updated_at,revoked_at)
                   VALUES (?,?,'IOS_APNS','synthetic-encrypted-token',?,clock_timestamp(),clock_timestamp(),?)""",
                installation, user, installation.toString().replace("-", "").repeat(2),
                if (revoked) now.atOffset(ZoneOffset.UTC) else null,
            )
            installation
        }

        suspend fun pushFlags(booking: UUID): Map<UUID, Boolean> = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                "SELECT recipient_user_id,push_requested FROM notification_events WHERE booking_id=? AND event_type='reminder_1h'",
            ).use { statement ->
                statement.setObject(1, booking)
                statement.executeQuery().use { rows ->
                    buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getBoolean(2)) }
                }
            }
        }

        suspend fun jobInstallations(booking: UUID): Set<UUID> = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                """SELECT j.installation_id FROM push_delivery_jobs AS j
                   JOIN notification_events AS e ON e.id=j.notification_event_id
                   WHERE e.booking_id=? AND e.event_type='reminder_1h' AND j.status='PENDING'""",
            ).use { statement ->
                statement.setObject(1, booking)
                statement.executeQuery().use { rows ->
                    buildSet { while (rows.next()) check(add(rows.getObject(1, UUID::class.java))) }
                }
            }
        }

        override fun close() {
            try {
                runBlocking {
                    database.withJdbcTransaction { connection ->
                        points.forEach { connection.execute("DELETE FROM points WHERE id=?", it) }
                        users.forEach { connection.execute("DELETE FROM users WHERE id=?", it) }
                    }
                }
            } finally {
                database.close()
            }
        }

        private fun Connection.execute(sql: String, vararg parameters: Any?) {
            prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeUpdate()
            }
        }
    }
}
