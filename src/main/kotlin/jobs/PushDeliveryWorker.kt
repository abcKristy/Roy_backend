package com.example.jobs

import com.example.persistence.DatabaseRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

data class PushPayload(
    val notificationId: UUID,
    val kind: String,
    val pointId: UUID?,
    val chatId: UUID?,
    val proposalId: UUID?,
)

enum class PushFailureCode { TIMEOUT, UNAVAILABLE, RATE_LIMITED, INVALID_TOKEN, REJECTED, CONFIGURATION }

sealed interface PushSendResult {
    data object Delivered : PushSendResult
    data class Retryable(val code: PushFailureCode) : PushSendResult
    data class Permanent(val code: PushFailureCode) : PushSendResult
}

/** The caller must supply a real provider adapter; there is intentionally no no-op sender. */
fun interface PushSender {
    suspend fun send(platform: String, token: String, payload: PushPayload): PushSendResult
}

fun interface PushTokenDecoder {
    fun decrypt(installationId: UUID, platform: String, ciphertext: String): String
}

data class PushDeliverySnapshot(val delivered: Long, val retried: Long, val failed: Long, val cancelled: Long, val reclaimed: Long)

/** Claim/validate/ack are short DB transactions. Provider I/O occurs only after claim commits. */
class PushDeliveryWorker(
    private val database: DatabaseRuntime,
    private val sender: PushSender,
    private val tokenDecoder: PushTokenDecoder,
    private val maxAttempts: Int = 5,
    private val leaseSeconds: Int = 45,
    private val providerTimeoutMillis: Long = 10_000,
) {
    init {
        require(maxAttempts in 1..20)
        require(leaseSeconds in 15..300)
        require(providerTimeoutMillis in 1_000 until leaseSeconds * 1_000L)
    }

    private val delivered = AtomicLong()
    private val retried = AtomicLong()
    private val failed = AtomicLong()
    private val cancelled = AtomicLong()
    private val reclaimed = AtomicLong()

    fun snapshot(): PushDeliverySnapshot = PushDeliverySnapshot(
        delivered.get(), retried.get(), failed.get(), cancelled.get(), reclaimed.get())

    suspend fun processBatch(limit: Int = 20): Int {
        require(limit in 1..100)
        reclaimExpired(limit)
        val claims = claim(limit)
        for (claim in claims) processOne(claim)
        return claims.size
    }

    suspend fun reclaimExpired(limit: Int = 100): Int {
        require(limit in 1..1000)
        val count = database.withJdbcTransaction { connection ->
            connection.prepareStatement(
                """WITH expired AS (
                       SELECT id FROM push_delivery_jobs
                       WHERE status='PROCESSING' AND lease_until<=clock_timestamp()
                       ORDER BY lease_until,id FOR UPDATE SKIP LOCKED LIMIT ?
                   ) UPDATE push_delivery_jobs AS j
                   SET status='PENDING',claim_token=NULL,lease_until=NULL,completed_at=NULL,
                       next_attempt_at=clock_timestamp()
                   FROM expired WHERE j.id=expired.id"""
            ).use { statement -> statement.setInt(1, limit); statement.executeUpdate() }
        }
        reclaimed.addAndGet(count.toLong())
        return count
    }

    /** Explicit operator retry; terminal FAILED jobs are never retried automatically. */
    suspend fun retryFailed(jobId: UUID): Boolean = database.withJdbcTransaction { connection ->
        connection.prepareStatement(
            """UPDATE push_delivery_jobs SET status='PENDING',attempts=0,last_error=NULL,completed_at=NULL,
               next_attempt_at=clock_timestamp() WHERE id=? AND status='FAILED'"""
        ).use { statement -> statement.setObject(1, jobId); statement.executeUpdate() == 1 }
    }

    suspend fun renewLease(jobId: UUID, token: UUID): Boolean = database.withJdbcTransaction { connection ->
        connection.prepareStatement(
            """UPDATE push_delivery_jobs SET lease_until=clock_timestamp()+(? * interval '1 second')
               WHERE id=? AND claim_token=? AND status='PROCESSING' AND lease_until>clock_timestamp()"""
        ).use { statement ->
            statement.setInt(1, leaseSeconds); statement.setObject(2, jobId); statement.setObject(3, token)
            statement.executeUpdate() == 1
        }
    }

    private data class Claim(val id: UUID, val token: UUID)
    private data class Candidate(
        val id: UUID, val eventId: UUID, val installationId: UUID, val recipientId: UUID,
        val platform: String, val ciphertext: String, val kind: String, val channel: String,
        val pointId: UUID?, val chatId: UUID?, val proposalId: UUID?, val bookingId: UUID?,
    ) {
        fun payload() = PushPayload(eventId, kind, pointId, chatId, proposalId)
    }

    private suspend fun claim(limit: Int): List<Claim> = database.withJdbcTransaction { connection ->
        connection.prepareStatement(
            """WITH due AS (
                   SELECT id FROM push_delivery_jobs
                   WHERE status='PENDING' AND next_attempt_at<=clock_timestamp()
                   ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT ?
               ) UPDATE push_delivery_jobs AS j
               SET status='PROCESSING',attempts=j.attempts+1,completed_at=NULL,claim_token=gen_random_uuid(),
                   lease_until=clock_timestamp()+(? * interval '1 second')
               FROM due WHERE j.id=due.id RETURNING j.id,j.claim_token"""
        ).use { statement ->
            statement.setInt(1, limit); statement.setInt(2, leaseSeconds)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(Claim(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java)))
            } }
        }
    }

    private suspend fun processOne(claim: Claim) {
        val candidate = database.withReadOnlyJdbcTransaction { connection -> loadValidCandidate(connection, claim) }
        if (candidate == null) {
            if (finish(claim, "CANCELLED", null)) cancelled.incrementAndGet()
            return
        }
        val token = try {
            tokenDecoder.decrypt(candidate.installationId, candidate.platform, candidate.ciphertext)
        } catch (_: Exception) {
            if (finish(claim, "FAILED", PushFailureCode.INVALID_TOKEN)) failed.incrementAndGet()
            return
        }
        // Decryption can outlive cancellation; recheck immediately before provider I/O.
        val stillValid = database.withReadOnlyJdbcTransaction { connection ->
            loadValidCandidate(connection, claim) != null
        }
        if (!stillValid) {
            if (finish(claim, "CANCELLED", null)) cancelled.incrementAndGet()
            return
        }
        val result = try {
            withTimeout(providerTimeoutMillis) { sender.send(candidate.platform, token, candidate.payload()) }
        } catch (timeout: TimeoutCancellationException) {
            PushSendResult.Retryable(PushFailureCode.TIMEOUT)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            PushSendResult.Retryable(PushFailureCode.UNAVAILABLE)
        }
        when (result) {
            PushSendResult.Delivered -> if (finish(claim, "SENT", null)) delivered.incrementAndGet()
            is PushSendResult.Permanent -> if (finish(claim, "FAILED", result.code)) failed.incrementAndGet()
            is PushSendResult.Retryable -> {
                val terminal = attempts(claim) >= maxAttempts
                if (finish(claim, if (terminal) "FAILED" else "PENDING", result.code)) {
                    (if (terminal) failed else retried).incrementAndGet()
                }
            }
        }
    }

    private suspend fun attempts(claim: Claim): Int = database.withReadOnlyJdbcTransaction { connection ->
        connection.prepareStatement(
            "SELECT attempts FROM push_delivery_jobs WHERE id=? AND claim_token=? AND status='PROCESSING'"
        ).use { statement ->
            statement.setObject(1, claim.id); statement.setObject(2, claim.token)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else maxAttempts }
        }
    }

    private suspend fun finish(claim: Claim, status: String, code: PushFailureCode?): Boolean =
        database.withJdbcTransaction { connection ->
            val attempts = connection.prepareStatement(
                "SELECT attempts FROM push_delivery_jobs WHERE id=? AND claim_token=? AND status='PROCESSING' FOR UPDATE"
            ).use { statement ->
                statement.setObject(1, claim.id); statement.setObject(2, claim.token)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else return@withJdbcTransaction false }
            }
            val effectiveStatus = if (status == "PENDING" && attempts >= maxAttempts) "FAILED" else status
            val delaySeconds = if (effectiveStatus == "PENDING") backoffSeconds(attempts) else 0
            connection.prepareStatement(
                """UPDATE push_delivery_jobs SET status=?,claim_token=NULL,lease_until=NULL,
                   next_attempt_at=clock_timestamp()+(? * interval '1 second'),last_error=?,
                   sent_at=CASE WHEN ?='SENT' THEN clock_timestamp() ELSE NULL END,
                   completed_at=CASE WHEN ? IN ('SENT','FAILED','CANCELLED')
                     THEN clock_timestamp() ELSE NULL END
                   WHERE id=? AND claim_token=? AND status='PROCESSING'"""
            ).use { statement ->
                statement.setString(1, effectiveStatus)
                statement.setLong(2, delaySeconds)
                statement.setString(3, code?.name)
                statement.setString(4, effectiveStatus)
                statement.setString(5, effectiveStatus)
                statement.setObject(6, claim.id)
                statement.setObject(7, claim.token)
                statement.executeUpdate() == 1
            }
        }

    private fun backoffSeconds(attempts: Int): Long {
        val base = (2L shl (attempts - 1).coerceIn(0, 9)).coerceAtMost(600)
        return base + ThreadLocalRandom.current().nextLong(base / 2 + 1)
    }

    private fun loadValidCandidate(connection: Connection, claim: Claim): Candidate? {
        val candidate = connection.prepareStatement(
            """SELECT e.id,e.recipient_user_id,d.installation_id,d.platform,d.token_ciphertext,
                      e.event_type,e.channel,e.point_id,e.chat_id,e.proposal_id,e.booking_id,
                      e.push_requested,u.deleted_at,d.revoked_at,d.user_id AS device_owner_id,
                      s.reminders_push_enabled,s.personal_push_enabled,s.extra_push_enabled
               FROM push_delivery_jobs AS j
               JOIN notification_events AS e ON e.id=j.notification_event_id
               JOIN device_registrations AS d ON d.installation_id=j.installation_id
               JOIN users AS u ON u.id=e.recipient_user_id
               JOIN notification_settings AS s ON s.user_id=e.recipient_user_id
               WHERE j.id=? AND j.claim_token=? AND j.status='PROCESSING'
                 AND j.lease_until>clock_timestamp()"""
        ).use { statement ->
            statement.setObject(1, claim.id); statement.setObject(2, claim.token)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                if (rows.getObject("deleted_at") != null || rows.getObject("revoked_at") != null ||
                    !rows.getBoolean("push_requested") ||
                    rows.getObject("recipient_user_id", UUID::class.java) !=
                        rows.getObject("device_owner_id", UUID::class.java)) return@use null
                val channel = rows.getString("channel")
                val enabled = when (channel) {
                    "REMINDERS" -> rows.getBoolean("reminders_push_enabled")
                    "PERSONAL" -> rows.getBoolean("personal_push_enabled")
                    "EXTRA" -> rows.getBoolean("extra_push_enabled")
                    else -> false
                }
                if (!enabled) return@use null
                Candidate(claim.id, rows.getObject("id", UUID::class.java),
                    rows.getObject("installation_id", UUID::class.java),
                    rows.getObject("recipient_user_id", UUID::class.java),
                    rows.getString("platform"), rows.getString("token_ciphertext"),
                    rows.getString("event_type"), channel,
                    rows.getObject("point_id", UUID::class.java), rows.getObject("chat_id", UUID::class.java),
                    rows.getObject("proposal_id", UUID::class.java), rows.getObject("booking_id", UUID::class.java))
            }
        } ?: return null
        if (!resourceStillValid(connection, candidate)) return null
        return candidate
    }

    private fun resourceStillValid(connection: Connection, candidate: Candidate): Boolean {
        val sql = when (candidate.kind) {
            "chat_message" -> """SELECT EXISTS(SELECT 1 FROM chat_memberships
                WHERE chat_id=? AND user_id=? AND left_at IS NULL)"""
            "proposal_vote", "proposal_ready" -> """SELECT EXISTS(
                SELECT 1 FROM meeting_proposals AS p
                JOIN chat_memberships AS m ON m.chat_id=p.chat_id
                WHERE p.id=? AND p.chat_id=? AND m.user_id=? AND m.left_at IS NULL)"""
            "nearby_meeting" -> """SELECT EXISTS(
                SELECT 1 FROM meeting_proposals AS p
                WHERE p.id=? AND p.lifecycle='OPEN' AND p.starts_at>clock_timestamp())"""
            "reminder_1h" -> """SELECT EXISTS(
                SELECT 1 FROM bookings AS b JOIN meeting_proposals AS p ON p.id=b.proposal_id
                JOIN chat_memberships AS m ON m.chat_id=p.chat_id
                WHERE b.id=? AND b.status='ACTIVE' AND p.id=? AND p.chat_id=?
                  AND m.user_id=? AND m.left_at IS NULL
                  AND b.created_at<=p.starts_at-interval '1 hour'
                  AND clock_timestamp()>=p.starts_at-interval '1 hour'
                  AND clock_timestamp()<p.starts_at-interval '58 minutes'
                  AND clock_timestamp()<p.starts_at)"""
            "booking_created", "booking_cancelled" -> """SELECT EXISTS(
                SELECT 1 FROM bookings AS b JOIN meeting_proposals AS p ON p.id=b.proposal_id
                JOIN chat_memberships AS m ON m.chat_id=p.chat_id
                WHERE b.id=? AND p.id=? AND p.chat_id=?
                  AND m.user_id=? AND m.left_at IS NULL AND b.status=?)"""
            else -> return false
        }
        return connection.prepareStatement(sql).use { statement ->
            when (candidate.kind) {
                "chat_message" -> {
                    statement.setObject(1, candidate.chatId); statement.setObject(2, candidate.recipientId)
                }
                "proposal_vote", "proposal_ready" -> {
                    statement.setObject(1, candidate.proposalId); statement.setObject(2, candidate.chatId)
                    statement.setObject(3, candidate.recipientId)
                }
                "nearby_meeting" -> statement.setObject(1, candidate.proposalId)
                "reminder_1h" -> {
                    statement.setObject(1, candidate.bookingId); statement.setObject(2, candidate.proposalId)
                    statement.setObject(3, candidate.chatId); statement.setObject(4, candidate.recipientId)
                }
                else -> {
                    statement.setObject(1, candidate.bookingId); statement.setObject(2, candidate.proposalId)
                    statement.setObject(3, candidate.chatId); statement.setObject(4, candidate.recipientId)
                    statement.setString(5, if (candidate.kind == "booking_created") "ACTIVE" else "CANCELLED")
                }
            }
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
    }
}
