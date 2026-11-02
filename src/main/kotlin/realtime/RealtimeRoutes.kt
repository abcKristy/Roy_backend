package com.example.realtime

import com.example.api.ApiException
import com.example.auth.SessionIdentity
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

private val identityKey = AttributeKey<SessionIdentity>("realtime-identity")
private const val HEARTBEAT_SECONDS = 30

fun Application.installRealtimeTransport(idleTimeoutMillis: Long = 60_000L) {
    require(idleTimeoutMillis in 60_000L..300_000L)
    install(WebSockets) {
        pingPeriodMillis = HEARTBEAT_SECONDS * 1000L
        timeoutMillis = idleTimeoutMillis
        maxFrameSize = 16L * 1024
    }
}

/** Authentication runs before upgrade so missing/revoked JWT receives HTTP 401. */
fun Route.realtimeRoutes(hub: RealtimeHub) {
    route("/api/v1/ws") {
        intercept(ApplicationCallPipeline.Call) {
            val identity = hub.authenticate(context.request.headers[HttpHeaders.Authorization])
            context.attributes.put(identityKey, identity)
        }
        webSocket {
            val authorization = call.request.headers[HttpHeaders.Authorization] ?: error("Authenticated header missing")
            val identity = call.attributes[identityKey]
            val client = hub.register(identity, authorization, this)
            val sender = launch {
                for (text in client.outbound) send(Frame.Text(text))
            }
            val authWatcher = launch {
                while (isActive) {
                    delay(HEARTBEAT_SECONDS * 1000L)
                    if (try { hub.authenticate(authorization) != identity } catch (_: ApiException) { true }) {
                        close(hub.sessionExpired())
                        break
                    }
                }
            }
            client.outbound.trySend(buildJsonObject {
                put("v", 1)
                put("type", "hello")
                put("serverTime", Instant.now().toString())
                put("heartbeatSeconds", HEARTBEAT_SECONDS)
            }.toString())
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    if (try { hub.authenticate(authorization) != identity } catch (_: ApiException) { true }) {
                        close(hub.sessionExpired())
                        break
                    }
                    val command = parseCommand(frame.readText())
                    val response = when (command) {
                        is Command.Subscribe -> {
                            val (accepted, rejected) = hub.subscribe(client, command.chatIds)
                            buildJsonObject {
                                put("v", 1); put("type", "subscribed"); put("requestId", command.requestId)
                                put("acceptedChatIds", ids(accepted)); put("rejectedChatIds", ids(rejected))
                            }
                        }
                        is Command.Unsubscribe -> {
                            hub.unsubscribe(client, command.chatIds)
                            buildJsonObject {
                                put("v", 1); put("type", "unsubscribed"); put("requestId", command.requestId)
                                put("chatIds", ids(command.chatIds))
                            }
                        }
                        is Command.Invalid -> invalidCommand(command.requestId)
                    }
                    client.outbound.trySend(response.toString())
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                hub.unregister(client)
                authWatcher.cancel()
                sender.cancel()
            }
        }
    }
}

private sealed interface Command {
    data class Subscribe(val requestId: String, val chatIds: List<UUID>) : Command
    data class Unsubscribe(val requestId: String, val chatIds: List<UUID>) : Command
    data class Invalid(val requestId: String?) : Command
}

private fun parseCommand(text: String): Command {
    if (text.toByteArray(Charsets.UTF_8).size > 16 * 1024) return Command.Invalid(null)
    val value = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        ?: return Command.Invalid(null)
    val requestId = (value["requestId"] as? JsonPrimitive)?.content?.takeIf { parseUuid(it) != null }
    if ((value["v"] as? JsonPrimitive)?.intOrNull != 1 || requestId == null) return Command.Invalid(requestId)
    val rawIds = value["chatIds"] as? JsonArray ?: return Command.Invalid(requestId)
    if (rawIds.size > 100) return Command.Invalid(requestId)
    val chatIds = rawIds.map { parseUuid((it as? JsonPrimitive)?.content ?: return Command.Invalid(requestId))
        ?: return Command.Invalid(requestId) }
    return when ((value["type"] as? JsonPrimitive)?.content) {
        "subscribe" -> Command.Subscribe(requestId, chatIds)
        "unsubscribe" -> Command.Unsubscribe(requestId, chatIds)
        else -> Command.Invalid(requestId)
    }
}

private fun parseUuid(text: String): UUID? = try {
    UUID.fromString(text).takeIf { it.toString() == text }
} catch (_: IllegalArgumentException) { null }

private fun invalidCommand(requestId: String?) = buildJsonObject {
    put("v", 1); put("type", "error"); put("code", "INVALID_FIELD")
    put("requestId", requestId?.let(::JsonPrimitive) ?: JsonNull)
}

private fun ids(values: List<UUID>) = JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
