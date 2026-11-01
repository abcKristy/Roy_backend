package com.example.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/** One process owns these polling loops. Close it before the database runtime. */
class JobRuntime(
    private val reminders: ReminderScheduler,
    private val expiry: ProposalExpiryScheduler,
    private val broadcaster: NotificationBroadcaster,
    private val cleanup: RetentionCleanup,
    private val push: PushDeliveryWorker? = null,
    private val media: MediaDeletionWorker? = null,
    private val onExpired: suspend (List<ExpiredProposal>) -> Unit = {},
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(JobRuntime::class.java)
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private var started = false

    @Synchronized
    fun start(): JobRuntime {
        check(!started) { "JobRuntime can start only once" }
        started = true
        loop("reminders", 30_000) { reminders.tick() }
        loop("proposal-expiry", 30_000) { onExpired(expiry.tick()) }
        loop("notification-broadcast", 2_000) { broadcaster.tick() }
        loop("retention", 3_600_000) { cleanup.runBatch() }
        if (push != null) loop("push-delivery", 2_000) { push.processBatch() }
        if (media != null) loop("media-deletion", 10_000) { media.processBatch() }
        return this
    }

    private fun loop(name: String, intervalMillis: Long, block: suspend () -> Unit) {
        scope.launch {
            while (true) {
                try {
                    block()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    log.warn("Background job {} failed", name, failure)
                }
                delay(intervalMillis)
            }
        }
    }

    suspend fun stop() { root.cancelAndJoin() }

    override fun close() { runBlocking { stop() } }
}
