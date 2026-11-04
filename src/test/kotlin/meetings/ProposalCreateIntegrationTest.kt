package com.example.meetings

import com.example.api.proposalJson
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProposalCreateIntegrationTest {
    @Test
    fun `member creates zero vote proposal and replay preserves exact snapshot without effects`() = runBlocking<Unit> {
        Fixture().use { f ->
            val command = f.command()
            assertEquals(CreateProposalResult.NotMember,
                f.service.create(command.copy(actorId = f.outsider)) { _, _ -> error("Nonmember callback") })
            assertEquals(State(0, 0, 0, 0, 0), f.state())
            val created = assertIs<CreateProposalResult.Created>(f.service.create(command) { _, view -> proposalJson(view) })
            with(created.proposal) {
                assertEquals(f.chats[0], chatId)
                assertEquals(f.actor, proposedByUserId)
                assertEquals(command.startsAt, startsAt)
                assertEquals(command.endsAt, endsAt)
                assertEquals(0, goingCount)
                assertFalse(viewerGoing)
                assertEquals("voting", status)
                assertNull(booking)
                assertEquals(0L, revision)
            }
            val expectedSnapshot = buildJsonObject { put("status", 201); put("body", proposalJson(created.proposal)) }
            assertEquals(expectedSnapshot, created.responseSnapshot)
            assertEquals("meeting_proposed", created.emissions.single().draft.kind.databaseValue)
            assertEquals(created.proposal.id, created.emissions.single().draft.proposalId)
            assertEquals(State(1, 0, 1, 1, 1), f.state())
            // A late create retry must not overwrite a newer persisted revision.
            f.connection().use { c -> execute(c, "UPDATE meeting_proposals SET revision=7 WHERE id=?", created.proposal.id) }
            assertEquals(expectedSnapshot, assertIs<CreateProposalResult.Replay>(
                f.service.create(command) { _, _ -> error("Replay callback") }).responseSnapshot)
            for (changed in listOf(
                command.copy(chatId = f.chats[1]),
                command.copy(startsAt = command.startsAt.plusSeconds(60)),
                command.copy(endsAt = command.endsAt.plusSeconds(60)),
            )) {
                assertEquals(CreateProposalResult.KeyReused,
                    f.service.create(changed) { _, _ -> error("Changed-key callback") })
            }
            assertEquals(State(1, 0, 1, 1, 1), f.state())
            f.connection().use { c ->
                c.prepareStatement("SELECT revision FROM meeting_proposals WHERE id=?").use { s ->
                    s.setObject(1, created.proposal.id)
                    s.executeQuery().use { rows -> check(rows.next()); assertEquals(7L, rows.getLong(1)) }
                }
                c.prepareStatement("SELECT result_ref,response_snapshot::text FROM operation_idempotency WHERE actor_user_id=? AND idempotency_key=?").use { s ->
                    s.setObject(1, f.actor); s.setString(2, command.idempotencyKey.toString())
                    s.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(created.proposal.id, rows.getObject(1, UUID::class.java))
                        assertEquals(expectedSnapshot, kotlinx.serialization.json.Json.parseToJsonElement(rows.getString(2)))
                    }
                }
            }
        }
    }

    @Test
    fun `failure after proposal and system write rolls back state and leaves key reusable`() = runBlocking<Unit> {
        Fixture().use { f ->
            val command = f.command()
            val before = f.state()
            assertEquals(State(0, 0, 0, 0, 0), before)
            assertFailsWith<SyntheticFailure> {
                f.service.create(command) { connection, _ ->
                    // Observe actual uncommitted writes, rather than throwing before the transaction.
                    assertEquals(State(1, 0, 1, 0, 1), f.state(connection))
                    throw SyntheticFailure()
                }
            }
            assertEquals(before, f.state())
            val created = assertIs<CreateProposalResult.Created>(f.service.create(command) { _, view -> proposalJson(view) })
            assertEquals(0, created.proposal.goingCount)
            assertFalse(created.proposal.viewerGoing)
            assertEquals(State(1, 0, 1, 1, 1), f.state())
            assertEquals(created.responseSnapshot, assertIs<CreateProposalResult.Replay>(
                f.service.create(command) { _, _ -> error("Retry replay callback") }).responseSnapshot)
            assertEquals(State(1, 0, 1, 1, 1), f.state())
        }
    }

    private class SyntheticFailure : RuntimeException()
    private data class State(val proposals: Int, val votes: Int, val messages: Int, val outcomes: Int, val messageSeq: Int)

    private class Fixture : AutoCloseable {
        private val url = testDatabaseUrl().also {
            require(Regex("^jdbc:postgresql://[^/]+/roy_m4_create$").matches(it))
        }
        private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
        private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
        private val secret = "m4-proposal-create-test-secret-at-least-32-bytes"
        private val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret,
            appEnvironment = AppEnvironment.LOCAL)).start()
        val actor = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        private val point = UUID.randomUUID()
        val chats = List(2) { UUID.randomUUID() }
        private val now = Instant.parse("2030-04-12T12:00:00Z")
        val service = ProposalService(runtime, secret, clock = Clock.fixed(now, ZoneOffset.UTC))

        init {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    for (id in listOf(actor, outsider)) {
                        val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                        execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                            VALUES (?,?,'synthetic-test-hash','Create fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                    }
                    execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                        VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic create fixture','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, actor)
                    for ((index, chat) in chats.withIndex()) {
                        val topic = UUID.randomUUID()
                        execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())",
                            topic, point, listOf("football", "basketball")[index])
                        execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chat, topic)
                        execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chat, actor)
                    }
                    c.commit()
                }
            } catch (failure: Throwable) {
                runtime.close()
                throw failure
            }
        }

        fun command() = CreateProposalCommand(actor, chats[0], UUID.randomUUID(), now.plusSeconds(3600), now.plusSeconds(7200))
        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        fun state(): State = connection().use(::state)
        fun state(c: Connection): State {
            fun count(sql: String, vararg ids: UUID): Int = c.prepareStatement(sql).use { s ->
                ids.forEachIndexed { index, id -> s.setObject(index + 1, id) }
                s.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
            }
            return State(
                count("SELECT count(*) FROM meeting_proposals WHERE chat_id IN (?,?)", chats[0], chats[1]),
                count("SELECT count(*) FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id IN (?,?)", chats[0], chats[1]),
                count("SELECT count(*) FROM messages WHERE chat_id IN (?,?)", chats[0], chats[1]),
                count("SELECT count(*) FROM operation_idempotency WHERE actor_user_id IN (?,?)", actor, outsider),
                count("SELECT COALESCE(sum(last_message_seq),0) FROM chats WHERE id IN (?,?)", chats[0], chats[1]),
            )
        }

        override fun close() {
            try {
                connection().use { c ->
                    c.autoCommit = false
                    execute(c, "DELETE FROM points WHERE id=?", point)
                    execute(c, "DELETE FROM users WHERE id IN (?,?)", actor, outsider)
                    c.commit()
                }
            } finally {
                runtime.close()
            }
        }
    }

    companion object {
        private fun execute(c: Connection, sql: String, vararg values: Any) {
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
                s.executeUpdate()
            }
        }
    }
}
