package com.example.jobs

import com.example.persistence.DatabaseRuntime

data class CleanupResult(
    val rateEvents: Int, val idempotencyRecords: Int, val expiredSessions: Int,
    val pushJobs: Int, val mediaJobs: Int,
)

/** Bounded batches; messages, reports and notification history have no arbitrary TTL. */
class RetentionCleanup(private val database: DatabaseRuntime) {
    suspend fun runBatch(limitPerTable: Int = 500): CleanupResult {
        require(limitPerTable in 1..5_000)
        return database.withJdbcTransaction { connection ->
            fun delete(table: String, predicate: String): Int = connection.prepareStatement(
                """WITH victims AS (SELECT ctid FROM $table WHERE $predicate LIMIT ?)
                   DELETE FROM $table AS target USING victims WHERE target.ctid=victims.ctid"""
            ).use { statement -> statement.setInt(1, limitPerTable); statement.executeUpdate() }
            CleanupResult(
                rateEvents = delete("message_rate_events", "accepted_at<clock_timestamp()-interval '1 day'"),
                idempotencyRecords = delete("operation_idempotency", "created_at<clock_timestamp()-interval '30 days'"),
                expiredSessions = delete("auth_sessions", "expires_at<clock_timestamp()"),
                pushJobs = delete("push_delivery_jobs",
                    "status IN ('SENT','FAILED','CANCELLED') AND completed_at<clock_timestamp()-interval '30 days'"),
                mediaJobs = delete("media_deletion_jobs",
                    "status IN ('DONE','FAILED','CANCELLED') AND completed_at<clock_timestamp()-interval '30 days'"),
            )
        }
    }
}
