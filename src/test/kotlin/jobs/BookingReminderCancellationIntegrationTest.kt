package com.example.jobs

import com.example.accounts.AccountDeletionResult
import com.example.accounts.AccountDeletionService
import com.example.api.proposalJson
import com.example.auth.Argon2idPasswordHasher
import com.example.auth.PasswordHashingWork
import com.example.auth.SessionIdentity
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.devices.DeviceService
import com.example.devices.DeviceTokenCipher
import com.example.devices.RegisterDeviceCommand
import com.example.devices.RegisterDeviceResult
import com.example.meetings.*
import com.example.notifications.NotificationService
import com.example.persistence.DatabaseRuntime
import com.example.persistence.HikariDatabaseBootstrap
import com.example.points.LeavePointChatResult
import com.example.points.PointWriteService
import com.example.testing.testDatabaseUrl
import kotlinx.coroutines.*
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BookingReminderCancellationIntegrationTest {
    @Test
    fun `all cancellation callers durably clear pending and processing reminders`() = runBlocking<Unit> {
        for (path in CancelPath.entries) Fixture().use { f ->
            f.schedule()
            f.markOneProcessing()
            f.cancel(path)
            f.assertCancelledJobs()
            assertEquals(1, f.visibleReminders(f.owner)); assertEquals(1, f.visibleReminders(f.recipient))
            val reminderRows = f.reminderRows()
            val jobs = f.jobs()
            repeat(2) {
                assertEquals(0, ReminderScheduler(f.db).tick().eventsCreated)
                f.repeatCancel(path)
            }
            assertEquals(reminderRows, f.reminderRows()); assertEquals(jobs, f.jobs())
            val calls = AtomicInteger()
            val worker = f.worker(PushSender { _, _, _ -> calls.incrementAndGet(); PushSendResult.Delivered })
            assertEquals(0, worker.processBatch()); assertEquals(0, calls.get())
            assertEquals(0, worker.reclaimExpired())
            for (job in jobs) assertFalse(worker.retryFailed(UUID.fromString(job[0])))
            f.assertCancelledJobs()
        }
    }

    @Test
    fun `scheduler selected active booking rechecks after cancellation before producing reminders`() = runBlocking<Unit> {
        Fixture().use { f ->
            val selected = CountDownLatch(1); val release = CountDownLatch(1)
            val paused = AfterFirstRead(f.db, selected, release)
            val scheduler = ReminderScheduler(paused)
            val tick = async(Dispatchers.IO) { scheduler.tick() }
            try {
                assertTrue(selected.await(10, TimeUnit.SECONDS), "Scheduler did not select its real booking candidate")
                f.cancel(CancelPath.UNVOTE)
                release.countDown()
                val result = withTimeout(25000) { tick.await() }
                assertEquals(1, result.bookingsVisited); assertEquals(0, result.eventsCreated)
                assertTrue(f.reminderRows().isEmpty()); assertTrue(f.jobs().isEmpty())
                assertEquals(0, scheduler.tick().eventsCreated)
            } finally { release.countDown(); withTimeout(25000) { tick.join() } }
        }
    }

    @Test
    fun `booking cancellation after decrypt but before provider suppresses stale claim`() = runBlocking<Unit> {
        Fixture().use { f ->
            f.schedule()
            val decrypted = CountDownLatch(1); val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val worker = f.worker(PushSender { _, _, _ -> calls.incrementAndGet(); PushSendResult.Delivered },
                PushTokenDecoder { installation, platform, ciphertext ->
                    val token = f.decrypt(installation, platform, ciphertext)
                    decrypted.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Decrypt gate timed out" }
                    token
                })
            val work = async(Dispatchers.IO) { worker.processBatch(1) }
            try {
                assertTrue(decrypted.await(10, TimeUnit.SECONDS), "Worker did not reach real decryption boundary")
                f.assertProcessingClaim()
                f.cancel(CancelPath.UNVOTE); f.assertCancelledJobs()
                release.countDown()
                assertEquals(1, withTimeout(25000) { work.await() })
                assertEquals(0, calls.get(), "Cancelled booking reached provider before send")
                f.assertCancelledJobs(); assertEquals(0, worker.processBatch())
                assertEquals(1, f.visibleReminders(f.recipient))
                assertEquals(0, ReminderScheduler(f.db).tick().eventsCreated)
            } finally { release.countDown(); withTimeout(25000) { work.join() } }
        }
    }

    @Test
    fun `already entered provider cannot undo cancellation through late delivered or retryable completion`() = runBlocking<Unit> {
        for (completion in listOf(PushSendResult.Delivered, PushSendResult.Retryable(PushFailureCode.UNAVAILABLE))) {
            Fixture().use { f ->
                f.schedule()
                val entered = CountDownLatch(1); val release = CompletableDeferred<PushSendResult>()
                val calls = AtomicInteger()
                val worker = f.worker(PushSender { _, _, payload ->
                    assertEquals("reminder_1h", payload.kind)
                    calls.incrementAndGet(); entered.countDown(); release.await()
                })
                val work = async(Dispatchers.IO) { worker.processBatch(1) }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS), "Provider adapter was not actually entered")
                    f.assertProcessingClaim(); f.cancel(CancelPath.LEAVE); f.assertCancelledJobs()
                    val cancelled = f.jobs()
                    release.complete(completion)
                    assertEquals(1, withTimeout(25000) { work.await() })
                    assertEquals(1, calls.get(), "An already entered provider call cannot be recalled")
                    assertEquals(cancelled, f.jobs()); f.assertCancelledJobs()
                    assertEquals(0L, worker.snapshot().delivered); assertEquals(0L, worker.snapshot().retried)
                    assertEquals(0, worker.processBatch()); assertEquals(1, calls.get())
                    assertEquals(0, ReminderScheduler(f.db).tick().eventsCreated)
                    assertEquals(1, f.visibleReminders(f.recipient))
                } finally { release.complete(completion); withTimeout(25000) { work.join() } }
            }
        }
    }

    private enum class CancelPath { UNVOTE, LEAVE, DELETE_ACCOUNT, AUTHOR_CLOSE }
    /** Pause after an actual committed read; no JDBC transaction/lock is held while the test cancels. */
    private class AfterFirstRead(private val delegate: DatabaseRuntime, private val selected: CountDownLatch,
        private val release: CountDownLatch) : DatabaseRuntime by delegate {
        private val reads = AtomicInteger()
        override suspend fun <T> withReadOnlyJdbcTransaction(block: (Connection) -> T): T {
            val result = delegate.withReadOnlyJdbcTransaction(block)
            if (reads.incrementAndGet() == 1) {
                selected.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Selected scheduler candidate gate timed out" }
            }
            return result
        }
        override fun close() = Unit
    }

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also { require(Regex("^jdbc:postgresql://[^/]+/roy_m4_cancel_jobs$").matches(it)) }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val databasePassword = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        private val secret = "m4-cancel-reminder-test-secret-at-least-32-bytes"
        private val hasher = Argon2idPasswordHasher(); private val hashing = PasswordHashingWork(hasher)
        private val password = "SyntheticReminderPassword42!"; private val encodedPassword = hasher.hash(password)
        val db = HikariDatabaseBootstrap(ServerConfig(url, user, databasePassword, secret, appEnvironment = AppEnvironment.LOCAL,
            databaseMaxPoolSize = 8, databaseLockTimeoutMs = 10_000, databaseStatementTimeoutMs = 20_000)).start()
        val owner = UUID.randomUUID(); private val actor = UUID.randomUUID(); val recipient = UUID.randomUUID()
        private val people = listOf(owner, actor, recipient); private val sessions = people.associateWith { UUID.randomUUID() }
        private val point = UUID.randomUUID(); private val chat = UUID.randomUUID(); private val proposal = UUID.randomUUID(); private val booking = UUID.randomUUID()
        private val installations = List(2) { UUID.randomUUID() }
        private val tokens = installations.associateWith { "local-reminder-" + UUID.randomUUID() }
        private val cipher = DeviceTokenCipher.fromBase64(Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in people) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,?,'Reminder fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone, encodedPassword)
                        val session = sessions.getValue(id)
                        execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                            session, id, session.toString().replace("-", "").repeat(2))
                        execute(c, "INSERT INTO notification_settings(user_id,personal_push_enabled,updated_at) VALUES (?,false,clock_timestamp())", id)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic reminder fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, owner)
                    val topic = UUID.randomUUID(); execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                    execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                    for (id in people) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                    execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,revision,created_at,updated_at)
                        VALUES (?,?,?,clock_timestamp()+interval '59 minutes 30 seconds',clock_timestamp()+interval '119 minutes 30 seconds',3,clock_timestamp()-interval '2 hours',clock_timestamp()-interval '2 hours')""", proposal, chat, owner)
                    for (id in listOf(owner, actor)) execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp()-interval '2 hours')", proposal, id)
                    execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp()-interval '2 hours')", booking, proposal, actor)
                    c.commit()
                }
                runBlocking {
                    val registry = DeviceService(db, cipher)
                    for (installation in installations) assertIs<RegisterDeviceResult.Registered>(registry.register(
                        RegisterDeviceCommand(recipient, installation, "IOS_APNS", tokens.getValue(installation))))
                }
            } catch (failure: Throwable) { db.close(); throw failure }
        }
        fun connection(): Connection = DriverManager.getConnection(url, user, databasePassword)
        suspend fun schedule() {
            assertEquals(3, ReminderScheduler(db).tick().eventsCreated)
            assertEquals(0, ReminderScheduler(db).tick().eventsCreated)
            assertEquals(2, jobs().size); assertTrue(jobs().all { it[1] == "PENDING" })
        }
        suspend fun cancel(path: CancelPath) {
            when (path) {
                CancelPath.UNVOTE -> assertIs<VoteResult.Updated>(ProposalService(db, secret).vote(VoteCommand(actor, proposal, false)))
                CancelPath.LEAVE -> assertIs<LeavePointChatResult.Left>(PointWriteService(db).leave(actor, chat))
                CancelPath.DELETE_ACCOUNT -> assertIs<AccountDeletionResult.Deleted>(AccountDeletionService(db, hashing).delete(SessionIdentity(actor, sessions.getValue(actor), 0), password))
                CancelPath.AUTHOR_CLOSE -> assertIs<CloseMeetingResult.Created>(BookingService(db).close(
                    CloseMeetingCommand(owner, proposal, UUID.randomUUID(), 3)) { _, view -> proposalJson(view) })
            }
            connection().use { c -> c.prepareStatement("SELECT status FROM bookings WHERE id=?").use { s ->
                s.setObject(1, booking); s.executeQuery().use { r -> assertTrue(r.next()); assertEquals("CANCELLED", r.getString(1)) }
            } }
        }
        suspend fun repeatCancel(path: CancelPath) {
            when (path) {
                CancelPath.UNVOTE -> assertIs<VoteResult.Unchanged>(ProposalService(db, secret).vote(VoteCommand(actor, proposal, false)))
                CancelPath.LEAVE -> assertEquals(LeavePointChatResult.AlreadyLeft, PointWriteService(db).leave(actor, chat))
                CancelPath.DELETE_ACCOUNT -> assertEquals(AccountDeletionResult.SessionExpired, AccountDeletionService(db, hashing).delete(SessionIdentity(actor, sessions.getValue(actor), 0), password))
                CancelPath.AUTHOR_CLOSE -> assertEquals(CloseMeetingResult.AlreadyClosed, BookingService(db).close(
                    CloseMeetingCommand(owner, proposal, UUID.randomUUID(), 4)) { _, _ -> error("Already closed callback") })
            }
        }
        fun jobs(): List<List<String?>> = connection().use { c -> c.prepareStatement("SELECT j.id,j.status,j.claim_token,j.lease_until,j.sent_at,j.completed_at,j.attempts,j.next_attempt_at FROM push_delivery_jobs j JOIN notification_events e ON e.id=j.notification_event_id WHERE e.booking_id=? AND e.event_type='reminder_1h' ORDER BY j.id").use { s ->
            s.setObject(1, booking); s.executeQuery().use { r -> buildList { while (r.next()) add((1..8).map { r.getString(it) }) } }
        } }
        fun reminderRows(): List<List<String?>> = connection().use { c -> c.prepareStatement("SELECT id,recipient_user_id,dedupe_key,visible_in_app FROM notification_events WHERE booking_id=? AND event_type='reminder_1h' ORDER BY recipient_user_id").use { s ->
            s.setObject(1, booking); s.executeQuery().use { r -> buildList { while (r.next()) add((1..4).map { r.getString(it) }) } }
        } }
        suspend fun visibleReminders(id: UUID): Int = NotificationService(db, secret).list(id, null, 100).items.count { it.kind == "reminder_1h" }
        fun markOneProcessing() = connection().use { c ->
            val id = UUID.fromString(jobs().first()[0])
            execute(c, "UPDATE push_delivery_jobs SET status='PROCESSING',attempts=1,claim_token=?,lease_until=clock_timestamp()+interval '5 minutes' WHERE id=?", UUID.randomUUID(), id)
        }
        fun assertProcessingClaim() {
            val processing = jobs().filter { it[1] == "PROCESSING" }
            assertEquals(1, processing.size); assertNotNull(processing.single()[2]); assertNotNull(processing.single()[3])
        }
        fun assertCancelledJobs() {
            val jobs = jobs(); assertEquals(2, jobs.size)
            for (job in jobs) {
                assertEquals("CANCELLED", job[1]); assertNull(job[2]); assertNull(job[3]); assertNull(job[4]); assertNotNull(job[5])
            }
        }
        fun decrypt(installation: UUID, platform: String, ciphertext: String): String {
            assertTrue(ciphertext.startsWith("v1:"), "Expected actual authenticated ciphertext")
            val token = cipher.decrypt(installation, platform, ciphertext)
            assertTrue(token == tokens.getValue(installation), "Synthetic token authenticated decryption failed")
            return token
        }
        fun worker(sender: PushSender, decoder: PushTokenDecoder = PushTokenDecoder(::decrypt)) =
            PushDeliveryWorker(db, sender, decoder, leaseSeconds = 60, providerTimeoutMillis = 20_000)
        override fun close() {
            try { connection().use { c -> c.autoCommit = false; execute(c, "DELETE FROM points WHERE id=?", point); for (id in people) execute(c, "DELETE FROM users WHERE id=?", id); c.commit() } }
            finally { db.close() }
        }
    }
    companion object { private fun execute(c: Connection, sql: String, vararg values: Any) { c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() } } }
}