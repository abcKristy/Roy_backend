package com.example.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordHashingWorkTest {
    @Test
    fun `hash work is bounded outside caller coroutine`() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val work = PasswordHashingWork(object : PasswordHasher {
            override fun hash(password: String): String {
                val current = active.incrementAndGet()
                peak.accumulateAndGet(current, ::maxOf)
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    return "hashed"
                } finally {
                    active.decrementAndGet()
                }
            }
            override fun verify(password: String, encodedHash: String) = false
            override fun needsRehash(encodedHash: String) = false
            override fun verifyUnknown(password: String) = Unit
        })
        val jobs = List(4) { async(Dispatchers.Default) { work.hash("secret") } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, active.get())
        } finally {
            release.countDown()
        }
        jobs.awaitAll()
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    }
}
