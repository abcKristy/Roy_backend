package com.example.jobs

import com.example.persistence.DatabaseRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

sealed interface MediaDeleteResult {
    data object Deleted : MediaDeleteResult
    data object AlreadyMissing : MediaDeleteResult
    data object Retryable : MediaDeleteResult
    data object Permanent : MediaDeleteResult
}

/** A missing object is a successful idempotent delete. */
fun interface MediaStore {
    suspend fun deleteIfExists(objectKey: String): MediaDeleteResult
}

data class MediaDeletionSnapshot(val done: Long, val retried: Long, val failed: Long, val reclaimed: Long)

class MediaDeletionWorker(
    private val database: DatabaseRuntime,
    private val store: MediaStore,
    private val maxAttempts: Int = 5,
    private val leaseSeconds: Int = 45,
    private val storeTimeoutMillis: Long = 10_000,
) {
    init {
        require(maxAttempts in 1..20)
        require(leaseSeconds in 15..300)
        require(storeTimeoutMillis in 1_000 until leaseSeconds * 1_000L)
    }
    private val done = AtomicLong()
    private val retried = AtomicLong()
    private val failed = AtomicLong()
    private val reclaimed = AtomicLong()

    fun snapshot() = MediaDeletionSnapshot(done.get(), retried.get(), failed.get(), reclaimed.get())

    suspend fun processBatch(limit: Int = 20): Int {
        require(limit in 1..100)
        reclaimExpired(limit)
        val claims = database.withJdbcTransaction { connection ->
            connection.prepareStatement(
                """WITH due AS (
                     SELECT id FROM media_deletion_jobs
                     WHERE status='PENDING' AND next_attempt_at<=clock_timestamp()
                     ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT ?
                   ) UPDATE media_deletion_jobs AS j
                   SET status='PROCESSING',attempts=j.attempts+1,completed_at=NULL,claim_token=gen_random_uuid(),
                       lease_until=clock_timestamp()+(? * interval '1 second')
                   FROM due WHERE j.id=due.id RETURNING j.id,j.claim_token,j.object_key"""
            ).use { statement ->
                statement.setInt(1, limit); statement.setInt(2, leaseSeconds)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(Claim(rows.getObject(1, UUID::class.java),
                        rows.getObject(2, UUID::class.java), rows.getString(3)))
                } }
            }
        }
        claims.forEach { processOne(it) }
        return claims.size
    }

    suspend fun reclaimExpired(limit: Int = 100): Int {
        require(limit in 1..1000)
        val count = database.withJdbcTransaction { connection ->
            connection.prepareStatement(
                """WITH expired AS (
                    SELECT id FROM media_deletion_jobs WHERE status='PROCESSING'
                      AND lease_until<=clock_timestamp()
                    ORDER BY lease_until,id FOR UPDATE SKIP LOCKED LIMIT ?
                ) UPDATE media_deletion_jobs AS j
                SET status='PENDING',claim_token=NULL,lease_until=NULL,completed_at=NULL,
                    next_attempt_at=clock_timestamp()
                FROM expired WHERE j.id=expired.id"""
            ).use { statement -> statement.setInt(1, limit); statement.executeUpdate() }
        }
        reclaimed.addAndGet(count.toLong())
        return count
    }

    suspend fun retryFailed(jobId: UUID): Boolean = database.withJdbcTransaction { connection ->
        connection.prepareStatement(
            """UPDATE media_deletion_jobs SET status='PENDING',attempts=0,completed_at=NULL,
               next_attempt_at=clock_timestamp() WHERE id=? AND status='FAILED'"""
        ).use { statement -> statement.setObject(1, jobId); statement.executeUpdate() == 1 }
    }

    suspend fun renewLease(jobId: UUID, token: UUID): Boolean = database.withJdbcTransaction { connection ->
        connection.prepareStatement(
            """UPDATE media_deletion_jobs SET lease_until=clock_timestamp()+(? * interval '1 second')
               WHERE id=? AND claim_token=? AND status='PROCESSING' AND lease_until>clock_timestamp()"""
        ).use { statement ->
            statement.setInt(1, leaseSeconds); statement.setObject(2, jobId); statement.setObject(3, token)
            statement.executeUpdate() == 1
        }
    }

    private data class Claim(val id: UUID, val token: UUID, val objectKey: String)

    private suspend fun processOne(claim: Claim) {
        val active = database.withReadOnlyJdbcTransaction { connection ->
            connection.prepareStatement(
                """SELECT 1 FROM media_deletion_jobs WHERE id=? AND claim_token=?
                   AND status='PROCESSING' AND lease_until>clock_timestamp()"""
            ).use { statement ->
                statement.setObject(1, claim.id); statement.setObject(2, claim.token)
                statement.executeQuery().use { it.next() }
            }
        }
        if (!active) return
        val result = try {
            withTimeout(storeTimeoutMillis) { store.deleteIfExists(claim.objectKey) }
        } catch (timeout: TimeoutCancellationException) {
            MediaDeleteResult.Retryable
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            MediaDeleteResult.Retryable
        }
        val status = when (result) {
            MediaDeleteResult.Deleted, MediaDeleteResult.AlreadyMissing -> "DONE"
            MediaDeleteResult.Permanent -> "FAILED"
            MediaDeleteResult.Retryable -> "PENDING"
        }
        val effective = finish(claim, status)
        if (effective == "DONE") done.incrementAndGet()
        if (effective == "FAILED") failed.incrementAndGet()
        if (effective == "PENDING") retried.incrementAndGet()
    }

    private suspend fun finish(claim: Claim, status: String): String? = database.withJdbcTransaction { connection ->
        val attempts = connection.prepareStatement(
            "SELECT attempts FROM media_deletion_jobs WHERE id=? AND claim_token=? AND status='PROCESSING' FOR UPDATE"
        ).use { statement ->
            statement.setObject(1, claim.id); statement.setObject(2, claim.token)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else return@withJdbcTransaction null }
        }
        val effective = if (status == "PENDING" && attempts >= maxAttempts) "FAILED" else status
        val delay = if (effective == "PENDING") {
            val base = (2L shl (attempts - 1).coerceIn(0, 9)).coerceAtMost(600)
            base + ThreadLocalRandom.current().nextLong(base / 2 + 1)
        } else 0L
        val changed = connection.prepareStatement(
            """UPDATE media_deletion_jobs SET status=?,claim_token=NULL,lease_until=NULL,
               completed_at=CASE WHEN ? IN ('DONE','FAILED','CANCELLED')
                 THEN clock_timestamp() ELSE NULL END,
               next_attempt_at=clock_timestamp()+(? * interval '1 second')
               WHERE id=? AND claim_token=? AND status='PROCESSING'"""
        ).use { statement ->
            statement.setString(1, effective); statement.setString(2, effective); statement.setLong(3, delay)
            statement.setObject(4, claim.id); statement.setObject(5, claim.token)
            statement.executeUpdate() == 1
        }
        if (changed) effective else null
    }
}
