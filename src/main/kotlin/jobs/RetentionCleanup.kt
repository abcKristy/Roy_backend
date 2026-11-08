package com.example.jobs

import com.example.persistence.DatabaseRuntime

data class CleanupResult(
    val rateEvents: Int, val idempotencyRecords: Int, val expiredSessions: Int,
    val pushJobs: Int, val mediaJobs: Int,
    val expiredPushJobs: Int,
)

/** Bounded batches; messages, reports and notification history have no arbitrary TTL. */
class RetentionCleanup(private val database: DatabaseRuntime) {
    suspend fun runCycle(limitPerTable: Int = 500, maxBatches: Int = 8) {
        require(maxBatches in 1..16)
        repeat(maxBatches) {
            val batch = runBatch(limitPerTable)
            val largest = with(batch) {
                maxOf(rateEvents, idempotencyRecords, expiredSessions, pushJobs, mediaJobs, expiredPushJobs)
            }
            if (largest < limitPerTable) return
        }
    }

    suspend fun runBatch(limitPerTable: Int = 500): CleanupResult {
        require(limitPerTable in 1..5_000)
        return database.withJdbcTransaction { connection ->
            fun delete(table: String, timestamp: String, predicate: String): Int = connection.prepareStatement(
                """WITH victims AS (
                     SELECT ctid FROM $table WHERE $predicate ORDER BY $timestamp LIMIT ? FOR UPDATE SKIP LOCKED
                   )
                   DELETE FROM $table AS target USING victims WHERE target.ctid=victims.ctid"""
            ).use { statement -> statement.setInt(1, limitPerTable); statement.executeUpdate() }
            CleanupResult(
                rateEvents = delete("message_rate_events", "accepted_at", "accepted_at<statement_timestamp()-interval '1 day'"),
                idempotencyRecords = delete("operation_idempotency", "created_at", "created_at<statement_timestamp()-interval '30 days'"),
                expiredSessions = delete("auth_sessions", "expires_at", "expires_at<statement_timestamp()"),
                pushJobs = delete("push_delivery_jobs", "completed_at",
                    "status IN ('SENT','FAILED','CANCELLED') AND completed_at<statement_timestamp()-interval '30 days'"),
                mediaJobs = delete("media_deletion_jobs", "completed_at",
                    "status='DONE' AND completed_at<statement_timestamp()-interval '30 days'"),
                expiredPushJobs = connection.prepareStatement(
                    """WITH victims AS (
                         SELECT j.id FROM push_delivery_jobs AS j
                         JOIN notification_events AS e ON e.id=j.notification_event_id
                         WHERE (j.status='PENDING' OR (j.status='PROCESSING' AND j.lease_until<=statement_timestamp()))
                           AND e.created_at<=statement_timestamp()-(? * interval '1 second')
                         ORDER BY e.created_at,j.id FOR UPDATE OF j SKIP LOCKED LIMIT ?
                       ) UPDATE push_delivery_jobs AS j
                       SET status='CANCELLED',claim_token=NULL,lease_until=NULL,completed_at=clock_timestamp()
                       FROM victims WHERE j.id=victims.id"""
                ).use { statement ->
                    statement.setInt(1, PUSH_JOB_TTL_SECONDS)
                    statement.setInt(2, limitPerTable)
                    statement.executeUpdate()
                },
            )
        }
    }
}
