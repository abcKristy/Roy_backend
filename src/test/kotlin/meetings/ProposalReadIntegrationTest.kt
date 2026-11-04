package com.example.meetings

import com.example.api.proposalJson
import com.example.chats.ChatReadService
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposalReadIntegrationTest {
    @Test
    fun `personal paged history preserves booked past and selects nearest future open pin`() = runBlocking<Unit> {
        val url = testDatabaseUrl()
        require(Regex("^jdbc:postgresql://[^/]+/roy_m4_read$").matches(url))
        val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        val secret = "m4-proposal-read-test-secret-at-least-32-bytes"
        val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        val actor = UUID.randomUUID()
        val peer = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val point = UUID.randomUUID()
        val topic = UUID.randomUUID()
        val chat = UUID.randomUUID()
        val far = UUID.randomUUID()
        val closedFuture = UUID.randomUUID()
        val near = UUID(0L, UUID.randomUUID().leastSignificantBits)
        val tied = UUID(1L, UUID.randomUUID().leastSignificantBits)
        val pastBooked = UUID.randomUUID()
        val pastUnbooked = UUID.randomUUID()
        val booking = UUID.randomUUID()
        val ordered = listOf(far, closedFuture, near, tied, pastBooked, pastUnbooked)
        fun connection() = DriverManager.getConnection(url, user, password)
        try {
            connection().use { c ->
                c.autoCommit = false
                for (id in listOf(actor, peer, outsider)) {
                    val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                    execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                        VALUES (?,?,'synthetic-test-hash','Read fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                }
                execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                    VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic read fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, actor)
                execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topic, point)
                execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                for (id in listOf(actor, peer)) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, id)
                val now = c.createStatement().use { s -> s.executeQuery("SELECT date_trunc('second',clock_timestamp())").use { rows ->
                    check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
                } }
                val starts = mapOf(far to now.plusSeconds(10800), closedFuture to now.plusSeconds(900),
                    near to now.plusSeconds(7200), tied to now.plusSeconds(7200),
                    pastBooked to now.minusSeconds(3600), pastUnbooked to now.minusSeconds(7200))
                for ((index, id) in ordered.withIndex()) {
                    val start = starts.getValue(id)
                    execute(c, """INSERT INTO meeting_proposals(id,chat_id,proposer_user_id,starts_at,ends_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?)""", id, chat, actor, start.atOffset(ZoneOffset.UTC),
                        start.plusSeconds(3600).atOffset(ZoneOffset.UTC), now.minusSeconds(index.toLong() + 60).atOffset(ZoneOffset.UTC),
                        now.atOffset(ZoneOffset.UTC))
                }
                execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", closedFuture)
                for ((proposal, voter) in listOf(near to actor, far to actor, far to peer, pastBooked to actor, pastBooked to peer)) {
                    execute(c, "INSERT INTO votes(proposal_id,user_id,created_at) VALUES (?,?,clock_timestamp())", proposal, voter)
                }
                execute(c, "INSERT INTO bookings(id,proposal_id,booked_by_user_id,created_at) VALUES (?,?,?,clock_timestamp())", booking, pastBooked, actor)
                c.commit()
            }
            val proposals = ProposalService(runtime, secret)
            val chats = ChatReadService(runtime, secret)
            assertNull(proposals.list(chat, outsider))
            assertNull(proposals.detail(closedFuture, outsider))
            assertNull(proposals.detail(pastBooked, outsider))
            assertNull(chats.detail(outsider, chat))
            val first = assertNotNull(proposals.list(chat, actor, limit = 2))
            assertEquals(ordered.take(2), first.items.map { it.id })
            val cursor = assertNotNull(first.nextCursor)
            val second = assertNotNull(proposals.list(chat, actor, cursor, 2))
            val third = assertNotNull(proposals.list(chat, actor, assertNotNull(second.nextCursor), 2))
            assertNull(third.nextCursor)
            assertEquals(ordered, (first.items + second.items + third.items).map { it.id })
            assertEquals(first, proposals.list(chat, actor, limit = 2))
            assertFailsWith<IllegalArgumentException> { proposals.list(chat, peer, cursor, 2) }
            assertFailsWith<IllegalArgumentException> { proposals.list(chat, actor, cursor, 3) }
            assertFailsWith<IllegalArgumentException> { proposals.list(chat, actor, "invalid-cursor", 2) }
            val mine = assertNotNull(proposals.detail(near, actor))
            val theirs = assertNotNull(proposals.detail(near, peer))
            assertEquals(1, mine.goingCount)
            assertEquals(mine.goingCount, theirs.goingCount)
            assertTrue(mine.viewerGoing)
            assertFalse(theirs.viewerGoing)
            assertEquals("true", proposalJson(mine)["viewerGoing"]!!.jsonPrimitive.content)
            assertEquals("false", proposalJson(theirs)["viewerGoing"]!!.jsonPrimitive.content)
            assertTrue(second.items.single { it.id == near }.viewerGoing)
            assertFalse(assertNotNull(proposals.list(chat, peer)).items.single { it.id == near }.viewerGoing)
            assertEquals("closed", assertNotNull(proposals.detail(closedFuture, actor)).status)
            assertEquals("closed", assertNotNull(proposals.detail(pastUnbooked, actor)).status)
            val historical = assertNotNull(proposals.detail(pastBooked, actor))
            assertEquals("booked", historical.status)
            assertEquals(booking, historical.booking?.id)
            assertEquals("active", historical.booking?.status)
            assertEquals(historical, third.items.single { it.id == pastBooked })
            assertEquals(near, assertNotNull(chats.detail(actor, chat)).summary.currentProposal?.id)
            assertEquals(near, chats.myChats(peer).items.single().currentProposal?.id)
            // Closing the nearest candidates advances the pin; none of the history is removed.
            connection().use { c -> execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", near) }
            assertEquals(tied, assertNotNull(chats.detail(actor, chat)).summary.currentProposal?.id)
            connection().use { c -> execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", tied) }
            val finalPin = assertNotNull(assertNotNull(chats.detail(actor, chat)).summary.currentProposal)
            assertEquals(far, finalPin.id)
            assertEquals("ready_to_book", finalPin.status)
            connection().use { c -> execute(c, "UPDATE meeting_proposals SET lifecycle='CLOSED',closed_reason='AUTHOR' WHERE id=?", far) }
            assertNull(assertNotNull(chats.detail(actor, chat)).summary.currentProposal)
            assertEquals(ordered, assertNotNull(proposals.list(chat, actor)).items.map { it.id })
            assertEquals("active", assertNotNull(proposals.detail(pastBooked, actor)).booking?.status)
            connection().use { c ->
                c.prepareStatement("SELECT status,cancelled_at,cancel_reason FROM bookings WHERE id=?").use { s ->
                    s.setObject(1, booking)
                    s.executeQuery().use { rows ->
                        check(rows.next()); assertEquals("ACTIVE", rows.getString(1)); assertNull(rows.getObject(2)); assertNull(rows.getString(3))
                    }
                }
            }
        } finally {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    execute(c, "DELETE FROM points WHERE id=?", point)
                    execute(c, "DELETE FROM users WHERE id IN (?,?,?)", actor, peer, outsider)
                    c.commit()
                }
            } finally {
                runtime.close()
            }
        }
    }

    private fun execute(c: Connection, sql: String, vararg values: Any) {
        c.prepareStatement(sql).use { s ->
            values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
            s.executeUpdate()
        }
    }
}
