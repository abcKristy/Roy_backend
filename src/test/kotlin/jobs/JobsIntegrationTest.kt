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
import kotlin.test.assertTrue

class JobsIntegrationTest {
    private val url = System.getenv("TEST_DATABASE_URL")
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

    private fun checkedUrl(): String? {
        val value = url ?: return null
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
                 true,true,clock_timestamp())"""
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
