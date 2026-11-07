package com.example.persistence

import java.sql.Connection
import java.sql.Array as SqlArray
import java.util.UUID

/** Select recipients inside the business transaction, before acquiring recipient barriers. */
class JdbcNotificationRecipients {
    fun activeChatMembers(
        connection: Connection,
        chatId: UUID,
        excludingUserId: UUID? = null,
    ): List<UUID> {
        requireTransaction(connection)
        return connection.prepareStatement(
            """SELECT m.user_id
               FROM chat_memberships AS m
               JOIN users AS u ON u.id = m.user_id AND u.deleted_at IS NULL
               WHERE m.chat_id = ? AND m.left_at IS NULL
                 AND m.user_id IS DISTINCT FROM ?
               ORDER BY m.user_id"""
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.setObject(2, excludingUserId)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getObject(1, UUID::class.java))
            } }
        }
    }

    /** The GiST prefilter leaves the exact 1000 m boundary to ST_Distance, as in point lookup. */
    fun nearbyForProposal(connection: Connection, proposalId: UUID, policyVersion: String): List<UUID> {
        requireTransaction(connection)
        return selectNearby(connection, proposalId, policyVersion)
    }

    /** Call after acquiring the recipient barriers for this fixed candidate set. */
    fun recheckNearbyForProposal(
        connection: Connection,
        proposalId: UUID,
        policyVersion: String,
        candidateIds: List<UUID>,
    ): List<UUID> {
        requireTransaction(connection)
        if (candidateIds.isEmpty()) return emptyList()
        val candidates = connection.createArrayOf("uuid", candidateIds.toTypedArray())
        try {
            connection.prepareStatement(
                "SELECT user_id FROM notification_settings WHERE user_id=ANY (?) ORDER BY user_id FOR SHARE"
            ).use { statement ->
                statement.setArray(1, candidates)
                // Fetch all rows so every settings lock is held before the next statement.
                statement.fetchSize = 0
                statement.executeQuery().close()
            }
            return selectNearby(connection, proposalId, policyVersion, candidates)
        } finally {
            candidates.free()
        }
    }

    private fun selectNearby(
        connection: Connection,
        proposalId: UUID,
        policyVersion: String,
        candidates: SqlArray? = null,
    ): List<UUID> {
        val candidateFilter = if (candidates == null) "" else "AND l.user_id = ANY (?)"
        return connection.prepareStatement(
            """SELECT l.user_id
               FROM meeting_proposals AS proposal
               JOIN chats AS chat ON chat.id = proposal.chat_id
               JOIN point_topics AS topic ON topic.id = chat.point_topic_id
               JOIN points AS point ON point.id = topic.point_id
               JOIN notification_locations AS l
                 ON ST_DWithin(l.position, point.location::geography, 1000.01)
               JOIN notification_settings AS settings ON settings.user_id = l.user_id
               JOIN users AS recipient ON recipient.id = l.user_id AND recipient.deleted_at IS NULL
               WHERE proposal.id = ? AND proposal.lifecycle = 'OPEN'
                 AND proposal.starts_at > clock_timestamp()
                 AND recipient.accepted_privacy_version = ?
                 AND ST_Distance(l.position, point.location::geography) <= 1000
                 AND (settings.extra_push_enabled OR settings.extra_in_app_enabled)
                 AND l.user_id IS DISTINCT FROM proposal.proposer_user_id
                 AND NOT EXISTS (
                     SELECT 1 FROM chat_memberships AS membership
                     WHERE membership.chat_id = proposal.chat_id
                       AND membership.user_id = l.user_id
                       AND membership.left_at IS NULL
                 )
                 $candidateFilter
               ORDER BY l.user_id"""
        ).use { statement ->
            statement.setObject(1, proposalId)
            statement.setString(2, policyVersion)
            if (candidates != null) statement.setArray(3, candidates)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getObject(1, UUID::class.java))
            } }
        }
    }

    private fun requireTransaction(connection: Connection) {
        require(!connection.autoCommit) { "Recipient selection must share the business transaction" }
        require(connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) {
            "Recipient selection must use READ COMMITTED"
        }
    }
}
