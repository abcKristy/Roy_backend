package com.example.meetings

import com.example.auth.JwtTokenIssuer
import com.example.config.AppEnvironment
import com.example.config.ServerConfig
import com.example.persistence.HikariDatabaseBootstrap
import com.example.testing.testDatabaseUrl
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** UUID-owned real database context shared by the M4 server acceptance tests. */
internal class MeetingAcceptanceFixture(
    databaseLockTimeoutMs: Long = 1_000,
    databaseStatementTimeoutMs: Long = 5_000,
    afterSeed: (MeetingAcceptanceFixture) -> Unit = {},
) : AutoCloseable {
    private val url = testDatabaseUrl().also {
        require(it == "jdbc:postgresql://127.0.0.1:55485/roy_m4_acceptance_fixture")
    }
    private val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER required")
    private val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD required")
    val secret = "m4-acceptance-fixture-local-secret-at-least-32"
    val runtime = HikariDatabaseBootstrap(ServerConfig(url, user, password, secret, appEnvironment = AppEnvironment.LOCAL,
        databaseLockTimeoutMs = databaseLockTimeoutMs, databaseStatementTimeoutMs = databaseStatementTimeoutMs)).start()
    val author = UUID.randomUUID()
    val voter = UUID.randomUUID()
    val observer = UUID.randomUUID()
    val outsider = UUID.randomUUID()
    val members = listOf(author, voter, observer)
    val people = members + outsider
    val point = UUID.randomUUID()
    val topics = List(2) { UUID.randomUUID() }
    val chats = List(2) { UUID.randomUUID() }
    private val sessions = people.associateWith { UUID.randomUUID() }
    val anchor: Instant = Instant.parse("2099-02-10T15:00:00Z")
    val slots = listOf(Slot(anchor, anchor.plusSeconds(600)), Slot(anchor.plusSeconds(300), anchor.plusSeconds(900)),
        Slot(anchor.plusSeconds(600), anchor.plusSeconds(1200)), Slot(anchor.plusSeconds(1800), anchor.plusSeconds(2400)))
    val clock = ControlledUtcClock(anchor.minusSeconds(86400))

    // The normal chain keeps PostgreSQL wall time. Boundary tests opt in explicitly.
    fun proposals(controlledTime: Boolean = false) = ProposalService(runtime, secret, clock = if (controlledTime) clock else null)
    fun bookings(controlledCloseTime: Boolean = false) = BookingService(runtime, closeClock = if (controlledCloseTime) clock else null)

    init {
        try {
            connection().use { c ->
                c.autoCommit = false
                for (id in people) {
                    val phone = "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0')
                    execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                        VALUES (?,?,'synthetic-test-hash','Meeting fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""", id, phone)
                    val session = sessions.getValue(id)
                    execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                        session, id, session.toString().replace("-", "").repeat(2))
                    execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
                }
                execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                    VALUES (?,ST_SetSRID(ST_MakePoint(37.62,55.75),4326),'Synthetic meeting acceptance','MANUAL',?,clock_timestamp(),clock_timestamp())""", point, author)
                for (index in chats.indices) {
                    execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,?,clock_timestamp())", topics[index], point, listOf("football", "basketball")[index])
                    execute(c, "INSERT INTO chats(id,point_topic_id,created_at) VALUES (?,?,clock_timestamp())", chats[index], topics[index])
                    for (id in members) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chats[index], id)
                }
                c.commit()
            }
            afterSeed(this)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun connection(): Connection = DriverManager.getConnection(url, user, password)
    fun bearer(actor: UUID): String {
        val now = Instant.now()
        return "Bearer " + JwtTokenIssuer(secret).issue(actor, sessions.getValue(actor), 0, now.minusSeconds(1), now.plusSeconds(3600))
    }

    /** Stable persisted state, including sequence/outcome effects, within this fixture's namespace. */
    fun state(): List<String> = connection().use { c ->
        c.prepareStatement("""SELECT 'point:'||row_to_json(p)::text FROM points p WHERE id=?
            UNION ALL SELECT 'chat:'||row_to_json(c)::text FROM chats c WHERE id IN (?,?)
            UNION ALL SELECT 'proposal:'||row_to_json(p)::text FROM meeting_proposals p WHERE chat_id IN (?,?)
            UNION ALL SELECT 'vote:'||row_to_json(v)::text FROM votes v JOIN meeting_proposals p ON p.id=v.proposal_id WHERE p.chat_id IN (?,?)
            UNION ALL SELECT 'booking:'||row_to_json(b)::text FROM bookings b JOIN meeting_proposals p ON p.id=b.proposal_id WHERE p.chat_id IN (?,?)
            UNION ALL SELECT 'message:'||row_to_json(m)::text FROM messages m WHERE chat_id IN (?,?)
            UNION ALL SELECT 'notification:'||row_to_json(n)::text FROM notification_events n WHERE point_id=?
            UNION ALL SELECT 'outcome:'||row_to_json(o)::text FROM operation_idempotency o WHERE actor_user_id IN (?,?,?,?) ORDER BY 1""").use { s ->
            (listOf(point) + chats + chats + chats + chats + chats + listOf(point) + people).forEachIndexed { index, id -> s.setObject(index + 1, id) }
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    override fun close() {
        try {
            connection().use { c ->
                c.autoCommit = false
                execute(c, "DELETE FROM points WHERE id=?", point)
                for (id in people) execute(c, "DELETE FROM users WHERE id=?", id)
                c.commit()
            }
        } finally { runtime.close() }
    }

    data class Slot(val startsAt: Instant, val endsAt: Instant) {
        fun overlaps(other: Slot) = startsAt < other.endsAt && other.startsAt < endsAt
    }

    class ControlledUtcClock(initial: Instant) : Clock() {
        private val current = AtomicReference(initial)
        fun set(instant: Instant) { current.set(instant) }
        override fun instant(): Instant = current.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    companion object {
        fun execute(c: Connection, sql: String, vararg values: Any?) {
            c.prepareStatement(sql).use { s -> values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate() }
        }
    }
}