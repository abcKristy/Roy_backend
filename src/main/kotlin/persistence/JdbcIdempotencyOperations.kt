package com.example.persistence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.postgresql.util.PGobject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A request authenticated before its actor was deactivated while waiting for a lock. */
class InactiveActorException : RuntimeException("Active actor is required")

/** All calls use the caller's transaction. Lock the actor before checking a key. */
class JdbcIdempotencyOperations {
    enum class Outcome { SUCCESS, QUOTA_EXCEEDED }

    data class Record(
        val requestHash: String,
        val outcome: Outcome,
        val resultRef: UUID?,
        val responseSnapshot: JsonObject?,
    )

    sealed interface Decision {
        data object New : Decision
        data class Replay(val record: Record) : Decision
        data object KeyReused : Decision
    }

    fun lockActorAndCheck(
        connection: Connection,
        actorId: UUID,
        operationType: String,
        key: UUID,
        requestHash: String,
    ): Decision {
        requireTransaction(connection)
        require(operationType.isNotBlank())
        require(requestHash.matches(Regex("[0-9a-f]{64}")))
        connection.prepareStatement("SELECT id FROM users WHERE id = ? AND deleted_at IS NULL FOR NO KEY UPDATE").use { statement ->
            statement.setObject(1, actorId)
            statement.executeQuery().use { result -> if (!result.next()) throw InactiveActorException() }
        }
        connection.prepareStatement(
            """SELECT request_hash, outcome, result_ref, response_snapshot
               FROM operation_idempotency
               WHERE actor_user_id = ? AND operation_type = ? AND idempotency_key = ?"""
        ).use { statement ->
            statement.setObject(1, actorId)
            statement.setString(2, operationType)
            statement.setString(3, key.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return Decision.New
                if (result.getString("request_hash") != requestHash) return Decision.KeyReused
                return Decision.Replay(
                    Record(
                        requestHash = requestHash,
                        outcome = Outcome.valueOf(result.getString("outcome")),
                        resultRef = result.getObject("result_ref", UUID::class.java),
                        responseSnapshot = result.getString("response_snapshot")?.let {
                            kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject
                        },
                    )
                )
            }
        }
    }

    /** Save a completed success after lockActorAndCheck. Confirmation conflicts leave the key unused. */
    fun recordSuccess(
        connection: Connection,
        actorId: UUID,
        operationType: String,
        key: UUID,
        requestHash: String,
        resultRef: UUID?,
        responseSnapshot: JsonObject,
    ) {
        insertRecord(connection, actorId, operationType, key, requestHash,
            Outcome.SUCCESS, resultRef, responseSnapshot, databaseNow(connection))
    }

    private fun insertRecord(
        connection: Connection,
        actorId: UUID,
        operationType: String,
        key: UUID,
        requestHash: String,
        outcome: Outcome,
        resultRef: UUID?,
        responseSnapshot: JsonObject,
        createdAt: Instant,
    ) {
        requireTransaction(connection)
        require(operationType.isNotBlank())
        require(requestHash.matches(Regex("[0-9a-f]{64}")))
        connection.prepareStatement(
            """INSERT INTO operation_idempotency
               (actor_user_id, operation_type, idempotency_key, request_hash, outcome,
                result_ref, response_snapshot, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, actorId)
            statement.setString(2, operationType)
            statement.setString(3, key.toString())
            statement.setString(4, requestHash)
            statement.setString(5, outcome.name)
            statement.setObject(6, resultRef)
            statement.setObject(7, jsonb(responseSnapshot))
            statement.setObject(8, createdAt.atOffset(ZoneOffset.UTC))
            check(statement.executeUpdate() == 1)
        }
    }

    /** Lock the actor, then use DB time for the block and saved response in one transaction. */
    fun recordQuotaExceeded(
        connection: Connection,
        actorId: UUID,
        operationType: String,
        key: UUID,
        requestHash: String,
        responseSnapshotFor: (blockedUntil: Instant) -> JsonObject,
    ): Instant {
        requireTransaction(connection)
        check(lockActorAndCheck(connection, actorId, operationType, key, requestHash) == Decision.New) {
            "A terminal idempotency result already exists"
        }
        val attemptedAt = databaseNow(connection)
        val blockedUntil = attemptedAt.plus(30, ChronoUnit.DAYS)
        val responseSnapshot = responseSnapshotFor(blockedUntil)
        connection.prepareStatement(
            "UPDATE users SET create_point_blocked_until = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL"
        ).use { statement ->
            statement.setObject(1, blockedUntil.atOffset(ZoneOffset.UTC))
            statement.setObject(2, attemptedAt.atOffset(ZoneOffset.UTC))
            statement.setObject(3, actorId)
            check(statement.executeUpdate() == 1) { "Active actor is required" }
        }
        insertRecord(connection, actorId, operationType, key, requestHash,
            Outcome.QUOTA_EXCEEDED, null, responseSnapshot, attemptedAt)
        return blockedUntil
    }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next())
            rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant()
        }
    }

    companion object {
        private val excludedFields = setOf("confirmNearby", "confirmOverlap", "expectedRevision")

        /** Caller supplies normalized business fields and route resource IDs in path order. */
        fun requestHash(operationType: String, routeResourceIds: List<UUID>, businessFields: JsonObject): String {
            require(operationType.isNotBlank())
            val input = JsonObject(
                mapOf(
                    "operation" to JsonPrimitive(operationType),
                    "resourceIds" to JsonArray(routeResourceIds.map { JsonPrimitive(it.toString()) }),
                    "business" to JsonObject(businessFields.filterKeys { it !in excludedFields }),
                )
            )
            val bytes = canonical(input).toByteArray(StandardCharsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { key ->
                "${JsonPrimitive(key)}:${canonical(value.getValue(key))}"
            }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            JsonNull -> "null"
            else -> value.toString()
        }
    }
}

internal fun requireTransaction(connection: Connection) {
    require(!connection.autoCommit) { "A caller-owned JDBC transaction is required" }
}

internal fun jsonb(value: JsonObject): PGobject = PGobject().apply {
    type = "jsonb"
    this.value = value.toString()
}
