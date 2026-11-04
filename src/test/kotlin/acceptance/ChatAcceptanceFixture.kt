package com.example.acceptance

import com.example.auth.JwtTokenIssuer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** Isolated synthetic namespace; never discovers or deletes another fixture's rows. */
class ChatAcceptanceFixture(val url: String, val user: String, private val password: String) : AutoCloseable {
    val member = UUID.randomUUID()
    val peer = UUID.randomUUID()
    val outsider = UUID.randomUUID()
    val reporters = List(16) { UUID.randomUUID() }
    val people = listOf(member, peer, outsider) + reporters
    val sessions = people.associateWith { UUID.randomUUID() }
    val points = List(2) { UUID.randomUUID() }
    val topics = List(2) { UUID.randomUUID() }
    val chats = List(2) { UUID.randomUUID() }
    val messages = List(4) { UUID.randomUUID() }

    init {
        require(Regex("^jdbc:postgresql://[^/]+/roy_m3_chat$").matches(url))
        require(System.getenv("TEST_DATABASE_DISPOSABLE") == "true")
        transaction { c ->
            for (id in people) {
                execute(c, """INSERT INTO users(id,phone_e164,password_hash,display_name,age_years,accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
                    VALUES (?,?,'synthetic-test-hash','Acceptance fixture',20,'test',clock_timestamp(),clock_timestamp(),clock_timestamp())""",
                    id, "+7" + id.mostSignificantBits.toULong().toString().takeLast(10).padStart(10, '0'))
                val session = sessions.getValue(id)
                execute(c, "INSERT INTO auth_sessions(id,user_id,created_at,expires_at,auth_version,refresh_token_hash) VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day',0,?)",
                    session, id, session.toString().replace("-", "").repeat(2))
                execute(c, "INSERT INTO notification_settings(user_id,updated_at) VALUES (?,clock_timestamp())", id)
            }
            for (index in 0..1) {
                execute(c, """INSERT INTO points(id,location,address,address_source,created_by_user_id,created_at,updated_at)
                    VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),?,'MANUAL',?,clock_timestamp(),clock_timestamp())""",
                    points[index], 70.0 + index, -20.0 + index, "Synthetic point $index", member)
                execute(c, "INSERT INTO point_topics(id,point_id,preset_key,created_at) VALUES (?,?,'football',clock_timestamp())", topics[index], points[index])
                execute(c, "INSERT INTO chats(id,point_topic_id,last_message_seq,created_at) VALUES (?,?,1,clock_timestamp())", chats[index], topics[index])
                val members = if (index == 0) listOf(member, peer) + reporters else listOf(peer)
                for (id in members) execute(c, "INSERT INTO chat_memberships(chat_id,user_id,joined_at) VALUES (?,?,clock_timestamp())", chats[index], id)
                execute(c, "INSERT INTO messages(id,chat_id,message_seq,author_user_id,type,body,created_at) VALUES (?,?,1,?,'TEXT',?,clock_timestamp())",
                    messages[index * 2], chats[index], peer, "Synthetic history $index")
                // The fixture uses the production canonical payload/sequence writer.
                c.prepareStatement("SELECT id FROM points WHERE id=? FOR UPDATE").use { s ->
                    s.setObject(1, points[index]); s.executeQuery().use { check(it.next()) }
                }
                val system = com.example.persistence.JdbcSystemMessageWriter().append(c, messages[index * 2 + 1],
                    com.example.persistence.SystemMessageDraft(com.example.persistence.SystemMessageKind.MEMBER_JOINED,
                        peer, points[index], chats[index]))
                check(system.messageSeq == 2L)
            }
        }
    }

    fun connection(): Connection = DriverManager.getConnection(url, user, password)
    fun <T> transaction(block: (Connection) -> T): T = connection().use { c ->
        c.autoCommit = false
        try { val result = block(c); c.commit(); result } catch (error: Throwable) { c.rollback(); throw error }
    }
    fun bearer(id: UUID, secret: String): String {
        val now = Instant.now()
        return "Bearer " + JwtTokenIssuer(secret).issue(id, sessions.getValue(id), 0, now.minusSeconds(1), now.plusSeconds(3600))
    }
    fun count(table: String): Int {
        require(table in setOf("users", "points", "chats", "messages", "reports"))
        val (column, ids) = when (table) {
            "users" -> "id" to people
            "points" -> "id" to points
            "chats" -> "id" to chats
            "messages" -> "chat_id" to chats
            else -> "reporter_user_id" to reporters
        }
        return connection().use { c ->
            val array = c.createArrayOf("uuid", ids.toTypedArray())
            try { c.prepareStatement("SELECT count(*) FROM $table WHERE $column=ANY(?)").use { s ->
                s.setArray(1, array); s.executeQuery().use { it.next(); it.getInt(1) }
            } } finally { array.free() }
        }
    }
    override fun close() = transaction { c ->
        // Reports have RESTRICT user FKs; all other dependent fixture records cascade.
        val pairs = listOf("DELETE FROM reports WHERE reporter_user_id=ANY(?)" to people,
            "DELETE FROM points WHERE id=ANY(?)" to points,
            "DELETE FROM users WHERE id=ANY(?)" to people)
        for ((sql, ids) in pairs) {
            val array = c.createArrayOf("uuid", ids.toTypedArray())
            try { c.prepareStatement(sql).use { it.setArray(1, array); it.executeUpdate() } } finally { array.free() }
        }
        Unit
    }
    private fun execute(c: Connection, sql: String, vararg values: Any) = c.prepareStatement(sql).use { s ->
        values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; check(s.executeUpdate() == 1)
    }
}

/** Contract-level assertion shared by fixture and REST acceptance; expected IDs come from the fixture namespace. */
internal fun assertMembershipSystemMessage(
    message: kotlinx.serialization.json.JsonObject, point: UUID, chat: UUID, actor: UUID, kind: String = "member_joined",
) {
    kotlin.test.assertEquals(kotlinx.serialization.json.JsonPrimitive("system"), message["type"])
    kotlin.test.assertEquals(kotlinx.serialization.json.JsonNull, message["author"])
    kotlin.test.assertEquals(kotlinx.serialization.json.JsonNull, message["text"])
    kotlin.test.assertEquals(kotlinx.serialization.json.JsonPrimitive(chat.toString()), message["chatId"])
    val payload = message["system"] as? kotlinx.serialization.json.JsonObject ?: error("System payload object required")
    kotlin.test.assertEquals(setOf("kind", "actorUserId", "pointId", "chatId", "proposalId", "bookingId", "startsAt", "endsAt", "reason"), payload.keys)
    for ((name, value) in mapOf("kind" to kind, "actorUserId" to actor.toString(), "pointId" to point.toString(), "chatId" to chat.toString()))
        kotlin.test.assertEquals(kotlinx.serialization.json.JsonPrimitive(value), payload[name])
    for (name in listOf("proposalId", "bookingId", "startsAt", "endsAt", "reason"))
        kotlin.test.assertEquals(kotlinx.serialization.json.JsonNull, payload[name])
}