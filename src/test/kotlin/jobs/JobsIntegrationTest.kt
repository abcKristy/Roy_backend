package com.example.jobs

import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobsIntegrationTest {
    private val url get() = com.example.testing.testDatabaseUrl()
    private val user = System.getenv("DATABASE_USER")
    private val password = System.getenv("DATABASE_PASSWORD")

    @Test
    fun `push claim sends once and rebind cancels an old owner job`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val account = UUID.randomUUID()
        val other = UUID.randomUUID()
        val point = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val installation = UUID.randomUUID()
        val event = UUID.randomUUID()
        val job = UUID.randomUUID()
        connection(dbUrl).use { c ->
            c.autoCommit = false
            seedUser(c, account); seedUser(c, other)
            seedChat(c, account, point, chat)
            c.prepareStatement(
                """INSERT INTO device_registrations
                   (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at)
                   VALUES (?,?,'ANDROID_RUSTORE','cipher','0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',clock_timestamp(),clock_timestamp())"""
            ).use { it.setObject(1, installation); it.setObject(2, account); it.executeUpdate() }
            insertEventJob(c, event, job, account, installation, point, chat)
            c.commit()
        }
        runtime(dbUrl).use { db ->
            var sends = 0
            val worker = PushDeliveryWorker(db,
                PushSender { _, token, payload ->
                    assertEquals("decoded-token", token)
                    assertEquals(event, payload.notificationId)
                    sends++
                    PushSendResult.Delivered
                }, PushTokenDecoder { _, _, _ -> "decoded-token" })
            assertEquals(1, worker.processBatch())
            assertEquals(1, sends)
            assertEquals(0, worker.processBatch())
            connection(dbUrl).use { c ->
                c.prepareStatement("SELECT status,attempts,claim_token,completed_at FROM push_delivery_jobs WHERE id=?")
                    .use { s -> s.setObject(1, job); s.executeQuery().use { r ->
                        assertTrue(r.next()); assertEquals("SENT", r.getString(1))
                        assertEquals(1, r.getInt(2)); assertEquals(null, r.getObject(3)); assertNotNull(r.getObject(4))
                    } }
            }
            val staleEvent = UUID.randomUUID(); val staleJob = UUID.randomUUID()
            connection(dbUrl).use { c ->
                c.autoCommit = false
                insertEventJob(c, staleEvent, staleJob, account, installation, point, chat)
                c.prepareStatement("UPDATE device_registrations SET user_id=? WHERE installation_id=?")
                    .use { it.setObject(1, other); it.setObject(2, installation); it.executeUpdate() }
                c.commit()
            }
            assertEquals(1, worker.processBatch())
            assertEquals(1, sends)
            connection(dbUrl).use { c ->
                c.prepareStatement("SELECT status,completed_at FROM push_delivery_jobs WHERE id=?")
                    .use { s -> s.setObject(1, staleJob); s.executeQuery().use { r ->
                        assertTrue(r.next()); assertEquals("CANCELLED", r.getString(1)); assertNotNull(r.getObject(2))
                    } }
            }
        }
    }

    @Test
    fun `reminder is deduplicated and time expiry closes only unbooked proposal`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val account = UUID.randomUUID(); val point = UUID.randomUUID(); val chat = UUID.randomUUID()
        val dueProposal = UUID.randomUUID(); val booking = UUID.randomUUID()
        val expiredProposal = UUID.randomUUID()
        connection(dbUrl).use { c ->
            c.autoCommit = false
            seedUser(c, account); seedChat(c, account, point, chat)
            c.prepareStatement(
                """INSERT INTO meeting_proposals
                   (id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                   VALUES (?,?,?,clock_timestamp()+interval '59 minutes',clock_timestamp()+interval '119 minutes',
                     clock_timestamp()-interval '2 hours',clock_timestamp()-interval '2 hours')"""
            ).use { it.setObject(1, dueProposal); it.setObject(2, chat); it.setObject(3, account); it.executeUpdate() }
            c.prepareStatement(
                """INSERT INTO bookings (id,proposal_id,booked_by_user_id,created_at)
                   VALUES (?,?,?,clock_timestamp()-interval '2 hours')"""
            ).use { it.setObject(1, booking); it.setObject(2, dueProposal); it.setObject(3, account); it.executeUpdate() }
            c.prepareStatement(
                """INSERT INTO meeting_proposals
                   (id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                   VALUES (?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '59 minutes',
                     clock_timestamp()-interval '2 hours',clock_timestamp()-interval '2 hours')"""
            ).use { it.setObject(1, expiredProposal); it.setObject(2, chat); it.setObject(3, account); it.executeUpdate() }
            c.commit()
        }
        runtime(dbUrl).use { db ->
            val reminders = ReminderScheduler(db)
            assertEquals(1, reminders.tick().eventsCreated)
            assertEquals(0, reminders.tick().eventsCreated)
            val emitted = mutableListOf<JsonObject>()
            val broadcaster = NotificationBroadcaster(db, publish = { recipient, _, payload, _ ->
                assertEquals(account, recipient); emitted += payload
            })
            assertTrue(broadcaster.tick() >= 1)
            assertTrue(emitted.any { it["kind"]?.jsonPrimitive?.content == "reminder_1h" })
            assertEquals(0, broadcaster.tick())
            val expired = ProposalExpiryScheduler(db).tick()
            assertTrue(expired.any { it.proposalId == expiredProposal })
            connection(dbUrl).use { c ->
                c.prepareStatement("SELECT lifecycle,closed_reason FROM meeting_proposals WHERE id=?")
                    .use { s -> s.setObject(1, expiredProposal); s.executeQuery().use { r ->
                        assertTrue(r.next()); assertEquals("CLOSED", r.getString(1)); assertEquals("TIME", r.getString(2))
                    } }
                c.prepareStatement("SELECT lifecycle FROM meeting_proposals WHERE id=?")
                    .use { s -> s.setObject(1, dueProposal); s.executeQuery().use { r ->
                        assertTrue(r.next()); assertEquals("OPEN", r.getString(1))
                    } }
            }
        }
    }

    @Test
    fun `missing media is done and stale lease is reclaimed`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val job = UUID.randomUUID()
        connection(dbUrl).use { c ->
            c.prepareStatement(
                """INSERT INTO media_deletion_jobs
                   (id,object_key,status,attempts,next_attempt_at,claim_token,lease_until,created_at)
                   VALUES (?,'missing-object','PROCESSING',1,clock_timestamp()-interval '1 hour',?,
                     clock_timestamp()-interval '1 minute',clock_timestamp()-interval '1 hour')"""
            ).use { it.setObject(1, job); it.setObject(2, UUID.randomUUID()); it.executeUpdate() }
        }
        runtime(dbUrl).use { db ->
            var deletes = 0
            val worker = MediaDeletionWorker(db, MediaStore { key ->
                assertEquals("missing-object", key); deletes++; MediaDeleteResult.AlreadyMissing
            })
            assertEquals(1, worker.processBatch())
            assertEquals(1, deletes)
            assertEquals(1, worker.snapshot().reclaimed)
            assertEquals(1, worker.snapshot().done)
            connection(dbUrl).use { c ->
                c.prepareStatement("SELECT status,completed_at FROM media_deletion_jobs WHERE id=?")
                    .use { s -> s.setObject(1, job); s.executeQuery().use { r ->
                        assertTrue(r.next()); assertEquals("DONE", r.getString(1)); assertNotNull(r.getObject(2))
                    } }
            }
        }
    }

    @Test
    fun `media claims at the attempt limit fail without another store call`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val expired = UUID.randomUUID()
        val pending = UUID.randomUUID()
        connection(dbUrl).use { connection ->
            connection.prepareStatement(
                """INSERT INTO media_deletion_jobs
                   (id,object_key,status,attempts,next_attempt_at,claim_token,lease_until,created_at)
                   VALUES (?,?,'PROCESSING',1,clock_timestamp(),gen_random_uuid(),
                           clock_timestamp()-interval '1 minute',clock_timestamp()),
                          (?,?,'PENDING',1,clock_timestamp(),NULL,NULL,clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, expired)
                statement.setString(2, expired.toString())
                statement.setObject(3, pending)
                statement.setString(4, pending.toString())
                statement.executeUpdate()
            }
        }
        runtime(dbUrl).use { database ->
            var deletes = 0
            val worker = MediaDeletionWorker(database, MediaStore { deletes++; MediaDeleteResult.AlreadyMissing }, maxAttempts = 1)
            assertEquals(0, worker.processBatch())
            assertEquals(0, deletes)
            assertEquals(1L, worker.snapshot().reclaimed)
            assertEquals(2L, worker.snapshot().failed)
            connection(dbUrl).use { connection ->
                connection.prepareStatement(
                    "SELECT status,attempts,completed_at,claim_token,lease_until FROM media_deletion_jobs WHERE id IN (?,?)"
                ).use { statement ->
                    statement.setObject(1, expired)
                    statement.setObject(2, pending)
                    statement.executeQuery().use { rows ->
                        repeat(2) {
                            assertTrue(rows.next())
                            assertEquals("FAILED", rows.getString(1))
                            assertEquals(1, rows.getInt(2))
                            assertNotNull(rows.getObject(3))
                            assertNull(rows.getObject(4))
                            assertNull(rows.getObject(5))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `retention leaves unresolved avatar erasure available for retry`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val job = UUID.randomUUID()
        connection(dbUrl).use { c ->
            c.prepareStatement(
                """INSERT INTO media_deletion_jobs(id,object_key,status,attempts,next_attempt_at,created_at,completed_at)
                   VALUES (?,?,'FAILED',5,clock_timestamp(),clock_timestamp()-interval '35 days',
                   clock_timestamp()-interval '31 days')"""
            ).use {
                it.setObject(1, job)
                it.setString(2, UUID.randomUUID().toString())
                it.executeUpdate()
            }
        }
        runtime(dbUrl).use { database ->
            RetentionCleanup(database).runBatch()
            val worker = MediaDeletionWorker(database, MediaStore { MediaDeleteResult.AlreadyMissing })
            assertTrue(worker.retryFailed(job))
            assertEquals(1, worker.processBatch())
            assertEquals(1L, worker.snapshot().done)
        }
    }

    @Test
    fun `media failure stays pending and an expired lease cannot acknowledge completion`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val job = UUID.randomUUID()
        val objectKey = UUID.randomUUID().toString()
        fun update(sql: String) = connection(dbUrl).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, job)
                statement.executeUpdate()
            }
        }
        fun status() = connection(dbUrl).use { connection ->
            connection.prepareStatement("SELECT status FROM media_deletion_jobs WHERE id=?").use { statement ->
                statement.setObject(1, job)
                statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
            }
        }
        connection(dbUrl).use { connection ->
            connection.prepareStatement(
                """INSERT INTO media_deletion_jobs(id,object_key,status,attempts,next_attempt_at,created_at)
                   VALUES (?,?,'PENDING',0,clock_timestamp(),clock_timestamp())"""
            ).use { statement ->
                statement.setObject(1, job)
                statement.setString(2, objectKey)
                statement.executeUpdate()
            }
        }
        try {
            runtime(dbUrl).use { database ->
                val failing = MediaDeletionWorker(database, MediaStore { key ->
                    assertEquals(objectKey, key)
                    MediaDeleteResult.Retryable
                })
                assertEquals(1, failing.processBatch())
                assertEquals("PENDING", status())
                assertEquals(1L, failing.snapshot().retried)
                update("UPDATE media_deletion_jobs SET next_attempt_at=clock_timestamp() WHERE id=?")

                val late = MediaDeletionWorker(database, MediaStore { key ->
                    assertEquals(objectKey, key)
                    update("UPDATE media_deletion_jobs SET lease_until=clock_timestamp() WHERE id=?")
                    MediaDeleteResult.Deleted
                })
                assertEquals(1, late.processBatch())
                assertEquals("PROCESSING", status())
                assertEquals(0L, late.snapshot().done)
            }
            runtime(dbUrl).use { restartedDatabase ->
                val restarted = MediaDeletionWorker(restartedDatabase, MediaStore { key ->
                    assertEquals(objectKey, key)
                    MediaDeleteResult.AlreadyMissing
                })
                assertEquals(1, restarted.processBatch())
                assertEquals(1L, restarted.snapshot().reclaimed)
                assertEquals("DONE", status())
            }
        } finally {
            update("DELETE FROM media_deletion_jobs WHERE id=?")
        }
    }

    @Test
    fun `expired push claim cannot acknowledge and restart safely recovers the same event`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val fixture = seedPushJob(dbUrl)
        runtime(dbUrl).use { db ->
            val first = PushDeliveryWorker(db, PushSender { _, _, payload ->
                assertEquals(fixture.event, payload.notificationId)
                connection(dbUrl).use { c ->
                    c.prepareStatement("UPDATE push_delivery_jobs SET lease_until=clock_timestamp() WHERE id=?")
                        .use { it.setObject(1, fixture.job); it.executeUpdate() }
                }
                PushSendResult.Delivered
            }, PushTokenDecoder { _, _, _ -> "token" })
            assertEquals(1, first.processBatch())
            assertEquals(0, first.snapshot().delivered)
            assertEquals("PROCESSING" to 1, pushState(dbUrl, fixture.job))

            val restarted = PushDeliveryWorker(db, PushSender { _, _, payload ->
                assertEquals(fixture.event, payload.notificationId)
                PushSendResult.Delivered
            }, PushTokenDecoder { _, _, _ -> "token" })
            assertEquals(1, restarted.processBatch())
            assertEquals(1, restarted.snapshot().reclaimed)
            assertEquals("SENT" to 2, pushState(dbUrl, fixture.job))
        }
    }

    @Test
    fun `push provider failures are retried only for an enabled platform and stop at the limit`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val fixture = seedPushJob(dbUrl)
        runtime(dbUrl).use { db ->
            val sender = PushSender { _, _, _ -> PushSendResult.Retryable(PushFailureCode.UNAVAILABLE) }
            val decoder = PushTokenDecoder { _, _, _ -> "token" }
            val disabled = PushDeliveryWorker(db, sender, decoder, enabledPlatforms = setOf("IOS_APNS"))
            assertEquals(0, disabled.processBatch())
            assertEquals("PENDING" to 0, pushState(dbUrl, fixture.job))

            val worker = PushDeliveryWorker(db, sender, decoder, maxAttempts = 2)
            assertEquals(1, worker.processBatch())
            assertEquals("PENDING" to 1, pushState(dbUrl, fixture.job))
            connection(dbUrl).use { c ->
                c.prepareStatement("UPDATE push_delivery_jobs SET next_attempt_at=clock_timestamp() WHERE id=?")
                    .use { it.setObject(1, fixture.job); it.executeUpdate() }
            }
            assertEquals(1, worker.processBatch())
            assertEquals("FAILED" to 2, pushState(dbUrl, fixture.job))
            assertEquals(0, worker.processBatch())
        }
    }

    @Test
    fun `invalid token revokes the current registration but cannot revoke a replacement`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        for (rotated in listOf(true, false)) {
            val fixture = seedPushJob(dbUrl)
            runtime(dbUrl).use { db ->
                val worker = PushDeliveryWorker(db, PushSender { _, _, _ ->
                    if (rotated) connection(dbUrl).use { c ->
                        c.prepareStatement("UPDATE device_registrations SET token_ciphertext='replacement' WHERE installation_id=?")
                            .use { it.setObject(1, fixture.installation); it.executeUpdate() }
                    }
                    PushSendResult.Permanent(PushFailureCode.INVALID_TOKEN)
                }, PushTokenDecoder { _, _, _ -> "token" })
                assertEquals(1, worker.processBatch())
                assertEquals("FAILED" to 1, pushState(dbUrl, fixture.job))
                connection(dbUrl).use { c ->
                    c.prepareStatement("SELECT revoked_at IS NOT NULL FROM device_registrations WHERE installation_id=?")
                        .use { s -> s.setObject(1, fixture.installation); s.executeQuery().use { r ->
                            assertTrue(r.next()); assertEquals(!rotated, r.getBoolean(1))
                        } }
                }
            }
        }
    }

    @Test
    fun `nearby delivery cancels stale consent and never forwards a private proposal target`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val stale = seedPushJob(dbUrl, nearbyConsent = "test")
        val allowed = seedPushJob(dbUrl, nearbyConsent = "location-current")
        runtime(dbUrl).use { db ->
            val worker = PushDeliveryWorker(db, PushSender { _, _, payload ->
                assertEquals(allowed.event, payload.notificationId)
                assertEquals("nearby_meeting", payload.kind)
                assertNotNull(payload.pointId)
                assertEquals(null, payload.chatId)
                assertEquals(null, payload.proposalId)
                PushSendResult.Delivered
            }, PushTokenDecoder { _, _, _ -> "token" }, locationPrivacyVersion = "location-current")
            assertEquals(2, worker.processBatch())
            assertEquals("CANCELLED" to 1, pushState(dbUrl, stale.job))
            assertEquals("SENT" to 1, pushState(dbUrl, allowed.job))
        }
    }

    @Test
    fun `restart does not send a job whose final lease has expired`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        val fixture = seedPushJob(dbUrl)
        connection(dbUrl).use { c ->
            c.prepareStatement("""UPDATE push_delivery_jobs SET status='PROCESSING',attempts=1,
                claim_token=gen_random_uuid(),lease_until=clock_timestamp() WHERE id=?""")
                .use { it.setObject(1, fixture.job); it.executeUpdate() }
        }
        runtime(dbUrl).use { db ->
            val restarted = PushDeliveryWorker(db, PushSender { _, _, _ -> error("Unexpected send") },
                PushTokenDecoder { _, _, _ -> "token" }, maxAttempts = 1)
            assertEquals(0, restarted.processBatch())
            assertEquals(1, restarted.snapshot().reclaimed)
            assertEquals("FAILED" to 1, pushState(dbUrl, fixture.job))
        }
    }

    @Test
    fun `stale push jobs cannot send after provider recovery and expire without a provider`(): Unit = runBlocking {
        val dbUrl = checkedUrl() ?: return@runBlocking
        fun age(event: UUID) = connection(dbUrl).use { connection ->
            connection.prepareStatement("UPDATE notification_events SET created_at=clock_timestamp()-interval '25 hours' WHERE id=?")
                .use { statement -> statement.setObject(1, event); statement.executeUpdate() }
        }
        val recovered = seedPushJob(dbUrl)
        age(recovered.event)
        runtime(dbUrl).use { database ->
            var sends = 0
            val worker = PushDeliveryWorker(database,
                PushSender { _, _, _ -> sends++; PushSendResult.Delivered },
                PushTokenDecoder { _, _, _ -> "token" })
            assertEquals(1, worker.processBatch(limit = 1))
            assertEquals(0, sends)
            assertEquals("CANCELLED" to 1, pushState(dbUrl, recovered.job))

            val pending = seedPushJob(dbUrl)
            val expiredLease = seedPushJob(dbUrl)
            val fresh = seedPushJob(dbUrl)
            age(pending.event)
            age(expiredLease.event)
            connection(dbUrl).use { connection ->
                connection.prepareStatement(
                    """UPDATE push_delivery_jobs SET status='PROCESSING',attempts=1,
                       claim_token=gen_random_uuid(),lease_until=clock_timestamp() WHERE id=?"""
                ).use { statement -> statement.setObject(1, expiredLease.job); statement.executeUpdate() }
            }
            assertEquals(2, RetentionCleanup(database).runBatch().expiredPushJobs)
            assertEquals("CANCELLED" to 0, pushState(dbUrl, pending.job))
            assertEquals("CANCELLED" to 1, pushState(dbUrl, expiredLease.job))
            assertEquals("PENDING" to 0, pushState(dbUrl, fresh.job))
            assertEquals(1, worker.processBatch(limit = 1))
            assertEquals(1, sends)
            assertEquals("SENT" to 1, pushState(dbUrl, fresh.job))
        }
    }

    private data class PushFixture(val event: UUID, val job: UUID, val installation: UUID)

    private fun seedPushJob(dbUrl: String, nearbyConsent: String? = null): PushFixture {
        val account = UUID.randomUUID()
        val point = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val fixture = PushFixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        connection(dbUrl).use { c ->
            c.autoCommit = false
            seedUser(c, account)
            seedChat(c, account, point, chat)
            c.prepareStatement(
                """INSERT INTO device_registrations
                   (installation_id,user_id,platform,token_ciphertext,token_digest,created_at,updated_at)
                   VALUES (?,?,'ANDROID_RUSTORE','cipher',?,clock_timestamp(),clock_timestamp())"""
            ).use {
                it.setObject(1, fixture.installation)
                it.setObject(2, account)
                it.setString(3, fixture.installation.toString().replace("-", "").repeat(2))
                it.executeUpdate()
            }
            insertEventJob(c, fixture.event, fixture.job, account, fixture.installation, point, chat)
            if (nearbyConsent != null) setNearby(c, fixture.event, account, chat, nearbyConsent)
            c.commit()
        }
        return fixture
    }

    private fun setNearby(c: Connection, event: UUID, account: UUID, chat: UUID, consent: String) {
        val proposal = UUID.randomUUID()
        c.prepareStatement(
            """INSERT INTO meeting_proposals (id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
               VALUES (?,?,?,clock_timestamp()+interval '1 hour',clock_timestamp()+interval '2 hours',
                 clock_timestamp(),clock_timestamp())"""
        ).use { it.setObject(1, proposal); it.setObject(2, chat); it.setObject(3, account); it.executeUpdate() }
        c.prepareStatement(
            "UPDATE notification_events SET channel='EXTRA',event_type='nearby_meeting',proposal_id=? WHERE id=?"
        ).use { it.setObject(1, proposal); it.setObject(2, event); it.executeUpdate() }
        c.prepareStatement("UPDATE users SET accepted_privacy_version=? WHERE id=?")
            .use { it.setString(1, consent); it.setObject(2, account); it.executeUpdate() }
        c.prepareStatement("UPDATE notification_settings SET extra_push_enabled=true WHERE user_id=?")
            .use { it.setObject(1, account); it.executeUpdate() }
        c.prepareStatement(
            """INSERT INTO notification_locations (user_id,position,updated_at)
               VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),clock_timestamp())"""
        ).use { it.setObject(1, account); it.executeUpdate() }
    }

    private fun pushState(dbUrl: String, job: UUID): Pair<String, Int> = connection(dbUrl).use { c ->
        c.prepareStatement("SELECT status,attempts FROM push_delivery_jobs WHERE id=?").use { s ->
            s.setObject(1, job)
            s.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getString(1) to rows.getInt(2)
            }
        }
    }

    private fun checkedUrl(): String? {
        val value = url
        require(value.matches(Regex("^jdbc:postgresql://[^/]+/roy_m1t2_step7jobs$")))
        HikariDatabaseBootstrap(ServerConfig(value,
            user ?: error("DATABASE_USER required"), password ?: error("DATABASE_PASSWORD required"),
            "jobs-integration-secret-at-least-32-bytes", appEnvironment = AppEnvironment.LOCAL)).start().close()
        return value
    }

    private fun connection(url: String): Connection = DriverManager.getConnection(url,
        user ?: error("DATABASE_USER required"), password ?: error("DATABASE_PASSWORD required"))

    private fun runtime(url: String) = HikariDatabaseBootstrap(ServerConfig(url,
        user ?: error("DATABASE_USER required"), password ?: error("DATABASE_PASSWORD required"),
        "jobs-integration-secret-at-least-32-bytes", appEnvironment = AppEnvironment.PRODUCTION)).start()

    private fun seedUser(c: Connection, id: UUID) {
        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
        c.prepareStatement(
            """INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
               accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
               VALUES (?,?,'test','Test',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())"""
        ).use { it.setObject(1, id); it.setString(2, phone); it.executeUpdate() }
        c.prepareStatement("INSERT INTO notification_settings (user_id,updated_at) VALUES (?,clock_timestamp())")
            .use { it.setObject(1, id); it.executeUpdate() }
    }

    private fun seedChat(c: Connection, owner: UUID, point: UUID, chat: UUID) {
        val topic = UUID.randomUUID()
        c.prepareStatement(
            """INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
               VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Test','MANUAL',?,clock_timestamp(),clock_timestamp())"""
        ).use { it.setObject(1, point); it.setObject(2, owner); it.executeUpdate() }
        c.prepareStatement("INSERT INTO point_topics (id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())")
            .use { it.setObject(1, topic); it.setObject(2, point); it.executeUpdate() }
        c.prepareStatement("INSERT INTO chats (id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())")
            .use { it.setObject(1, chat); it.setObject(2, topic); it.executeUpdate() }
        c.prepareStatement("INSERT INTO chat_memberships (chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())")
            .use { it.setObject(1, chat); it.setObject(2, owner); it.executeUpdate() }
    }

    private fun insertEventJob(c: Connection, event: UUID, job: UUID, account: UUID,
                               installation: UUID, point: UUID, chat: UUID) {
        c.prepareStatement(
            """INSERT INTO notification_events
               (id,recipient_user_id,channel,event_type,dedupe_key,point_id,chat_id,display_snapshot,
                visible_in_app,push_requested,created_at)
               VALUES (?,?,'PERSONAL','chat_message',?, ?,?,'{"title":"Test","body":"Test"}'::jsonb,
                 true,true,clock_timestamp()-interval '2 hours')"""
        ).use { s ->
            s.setObject(1, event); s.setObject(2, account); s.setString(3, "job:$job")
            s.setObject(4, point); s.setObject(5, chat); s.executeUpdate()
        }
        c.prepareStatement(
            """INSERT INTO push_delivery_jobs (id,notification_event_id,installation_id,next_attempt_at)
               VALUES (?,?,?,clock_timestamp())"""
        ).use { s -> s.setObject(1, job); s.setObject(2, event); s.setObject(3, installation); s.executeUpdate() }
    }
}
