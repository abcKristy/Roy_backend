package com.example.realtime

import com.example.api.ApiException
import com.example.auth.SessionAccessService
import com.example.auth.SessionIdentity
import com.example.persistence.DatabaseRuntime
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RealtimeScopeKind { CHAT, USER }

enum class RealtimeEventType(val wireValue: String) {
    CHAT_MESSAGE_CREATED("chat.message.created"),
    CHAT_MEMBERSHIP_CHANGED("chat.membership.changed"),
    POINT_CHANGED("point.changed"),
    POINT_DELETED("point.deleted"),
    PROPOSAL_UPDATED("proposal.updated"),
    BOOKING_CHANGED("booking.changed"),
    NOTIFICATION_CREATED("notification.created"),
    SESSION_REVOKED("session.revoked"),
}

data class RealtimeEvent(
    val id: UUID,
    val type: RealtimeEventType,
    val scopeKind: RealtimeScopeKind,
    val scopeId: UUID,
    val occurredAt: Instant,
    val payload: JsonObject,
) {
    fun toWire(): String = buildJsonObject {
        put("v", 1)
        put("eventId", id.toString())
        put("type", type.wireValue)
        put("scope", buildJsonObject {
            put("kind", scopeKind.name.lowercase())
            put("id", scopeId.toString())
        })
        put("occurredAt", occurredAt.toString())
        put("payload", payload)
    }.toString()
}

/** In-process best-effort delivery; REST/DB remain the source for reconnect recovery. */
class RealtimeHub(
    private val database: DatabaseRuntime,
    private val sessions: SessionAccessService,
) {
    internal class Client(
        val identity: SessionIdentity,
        val authorization: String,
        val socket: DefaultWebSocketServerSession,
    ) {
        val id: UUID = UUID.randomUUID()
        val subscriptions: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val outbound = Channel<String>(64)
    }

    private val clients = ConcurrentHashMap<UUID, Client>()

    suspend fun authenticate(authorization: String?): SessionIdentity = sessions.currentIdentity(authorization)

    internal fun register(identity: SessionIdentity, authorization: String, socket: DefaultWebSocketServerSession): Client =
        Client(identity, authorization, socket).also { clients[it.id] = it }

    internal fun unregister(client: Client) {
        clients.remove(client.id)
        client.outbound.close()
    }

    internal suspend fun subscribe(client: Client, requested: List<UUID>): Pair<List<UUID>, List<UUID>> {
        val accepted = database.withReadOnlyJdbcTransaction { connection ->
            val ids = connection.createArrayOf("uuid", requested.toTypedArray())
            try {
                connection.prepareStatement(
                    """SELECT chat_id FROM chat_memberships
                       WHERE user_id=? AND left_at IS NULL AND chat_id=ANY (?)"""
                ).use { statement ->
                    statement.setObject(1, client.identity.userId)
                    statement.setArray(2, ids)
                    statement.executeQuery().use { rows -> buildSet {
                        while (rows.next()) add(rows.getObject(1, UUID::class.java))
                    } }
                }
            } finally { ids.free() }
        }
        val yes = requested.distinct().filter { it in accepted }
        val no = requested.distinct().filter { it !in accepted }
        client.subscriptions.addAll(yes)
        return yes to no
    }

    internal fun unsubscribe(client: Client, requested: List<UUID>) {
        client.subscriptions.removeAll(requested.toSet())
    }

    /** Call only after the producing transaction commits. No replay is promised. */
    suspend fun publishCommitted(event: RealtimeEvent) {
        require(event.type != RealtimeEventType.SESSION_REVOKED) { "Session revocation is session-scoped" }
        val wire = event.toWire()
        withTimeoutOrNull(1_500) {
            coroutineScope {
                for (client in clients.values) {
                    val eligible = when (event.scopeKind) {
                        RealtimeScopeKind.USER -> client.identity.userId == event.scopeId
                        RealtimeScopeKind.CHAT -> event.scopeId in client.subscriptions
                    }
                    if (!eligible) continue
                    launch {
                        try {
                            if (!isActive(client)) {
                                closeBestEffort(client, sessionExpired())
                            } else if (event.scopeKind == RealtimeScopeKind.CHAT &&
                                !hasMembership(client.identity.userId, event.scopeId)) {
                                client.subscriptions.remove(event.scopeId)
                            } else if (client.outbound.trySend(wire).isFailure) {
                                closeBestEffort(client, CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Slow client"))
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // In-process delivery is best effort; REST recovers missed events.
                        }
                    }
                }
            }
        }
    }

    private suspend fun closeBestEffort(client: Client, reason: CloseReason) {
        unregister(client)
        withTimeoutOrNull(500) { client.socket.close(reason) }
    }

    /** Re-read committed membership state; a deleted chat has no remaining subscribers. */
    suspend fun publishMembershipChanged(chatId: UUID, userId: UUID, action: String) {
        require(action == "joined" || action == "left")
        val count = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM chat_memberships WHERE chat_id=? AND left_at IS NULL"
            ).use { statement ->
                statement.setObject(1, chatId)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
        val event = RealtimeEvent(
            UUID.randomUUID(), RealtimeEventType.CHAT_MEMBERSHIP_CHANGED,
            RealtimeScopeKind.CHAT, chatId, Instant.now(), buildJsonObject {
                put("chatId", chatId.toString())
                put("userId", userId.toString())
                put("participantCount", count)
                put("action", action)
            },
        )
        publishCommitted(event)
        publishCommitted(event.copy(id = UUID.randomUUID(), scopeKind = RealtimeScopeKind.USER, scopeId = userId))
        publishPointChangedForChat(chatId)
    }

    /** Re-read committed point state and notify members across all its topics, without a chat subscription. */
    suspend fun publishPointChangedForChat(chatId: UUID) {
        val change = database.withReadOnlyJdbcTransaction { connection ->
            val point = connection.prepareStatement(
                """SELECT point.id,point.updated_at FROM chats AS chat
                   JOIN point_topics AS topic ON topic.id=chat.point_topic_id
                   JOIN points AS point ON point.id=topic.point_id WHERE chat.id=?"""
            ).use { statement ->
                statement.setObject(1, chatId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else rows.getObject(1, UUID::class.java) to
                        rows.getObject(2, OffsetDateTime::class.java).toInstant()
                }
            } ?: return@withReadOnlyJdbcTransaction null
            val recipients = connection.prepareStatement(
                """SELECT DISTINCT membership.user_id FROM chat_memberships AS membership
                   JOIN users AS recipient ON recipient.id=membership.user_id AND recipient.deleted_at IS NULL
                   JOIN chats AS chat ON chat.id=membership.chat_id
                   JOIN point_topics AS topic ON topic.id=chat.point_topic_id
                   WHERE topic.point_id=? AND membership.left_at IS NULL"""
            ).use { statement ->
                statement.setObject(1, point.first)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getObject(1, UUID::class.java))
                } }
            }
            Triple(point.first, point.second, recipients)
        } ?: return
        for (recipientId in change.third) {
            publishCommitted(RealtimeEvent(UUID.randomUUID(), RealtimeEventType.POINT_CHANGED,
                RealtimeScopeKind.USER, recipientId, change.second, buildJsonObject {
                    put("pointId", change.first.toString())
                    put("updatedAt", change.second.toString())
                }))
        }
    }

    /** Invoke after revocation commits; only matching jti sockets receive the event and close. */
    suspend fun closeRevokedSession(sessionId: UUID, reason: String = "logout") {
        require(reason in setOf("logout", "password_changed", "expired"))
        coroutineScope {
            clients.values.filter { it.identity.sessionId == sessionId }.forEach { client ->
                launch {
                    val event = RealtimeEvent(UUID.randomUUID(), RealtimeEventType.SESSION_REVOKED,
                        RealtimeScopeKind.USER, client.identity.userId, Instant.now(),
                        buildJsonObject { put("reason", reason) })
                    try {
                        client.socket.outgoing.trySend(Frame.Text(event.toWire()))
                        withTimeoutOrNull(500) { client.socket.close(sessionExpired()) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        unregister(client)
                    }
                }
            }
        }
    }

    private suspend fun isActive(client: Client): Boolean = try {
        sessions.currentIdentity(client.authorization) == client.identity
    } catch (_: ApiException) {
        false
    }

    private suspend fun hasMembership(userId: UUID, chatId: UUID): Boolean = database.withReadOnlyJdbcTransaction { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM chat_memberships WHERE user_id=? AND chat_id=? AND left_at IS NULL"
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { it.next() }
        }
    }

    internal fun sessionExpired() = CloseReason(4401.toShort(), "Session expired")
}
