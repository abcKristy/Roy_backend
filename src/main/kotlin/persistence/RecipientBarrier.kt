package com.example.persistence

import java.sql.Connection
import java.util.UUID

enum class RecipientLockMode { SHARED, EXCLUSIVE }

data class RecipientLockClaim(val userId: UUID, val mode: RecipientLockMode)

/** Acquire the complete recipient set after business locks; never upgrade a held lock. */
class RecipientBarrier private constructor(
    private val connection: Connection,
    private val claims: Map<UUID, RecipientLockMode>,
) {
    companion object {
        fun acquire(connection: Connection, requested: Collection<RecipientLockClaim>): RecipientBarrier {
            require(!connection.autoCommit) { "Recipient barriers require an explicit transaction" }
            require(connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) {
                "Recipient barriers require READ COMMITTED for a fresh active-user check"
            }
            val claims = mutableMapOf<UUID, RecipientLockMode>()
            requested.forEach { claim ->
                val existing = claims.putIfAbsent(claim.userId, claim.mode)
                require(existing == null || existing == claim.mode) {
                    "A recipient lock cannot be upgraded or downgraded in one set"
                }
            }
            claims.entries.sortedWith(
                compareBy<Map.Entry<UUID, RecipientLockMode>> { AdvisoryLockKeys.recipient(it.key) }
                    .thenBy { it.key },
            ).forEach { (userId, mode) ->
                connection.acquireTransactionAdvisoryLock(
                    AdvisoryLockKeys.recipient(userId),
                    shared = mode == RecipientLockMode.SHARED,
                )
            }
            return RecipientBarrier(connection, claims)
        }
    }

    /** This SELECT must run after the advisory lock, in a fresh READ COMMITTED statement. */
    fun isActiveSharedRecipient(connection: Connection, userId: UUID): Boolean {
        require(this.connection === connection) { "Recipient barrier belongs to a different connection" }
        require(claims[userId] == RecipientLockMode.SHARED) { "A shared recipient lock is required" }
        connection.prepareStatement("SELECT deleted_at IS NULL FROM users WHERE id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rows -> return rows.next() && rows.getBoolean(1) }
        }
    }
}
