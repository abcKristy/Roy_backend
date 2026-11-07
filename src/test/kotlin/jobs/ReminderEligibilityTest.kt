package com.example.jobs

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderEligibilityTest {
    private val due = Instant.parse("2026-11-07T12:00:00Z")
    private val start = due.plusSeconds(3600)

    @Test
    fun `window includes due and excludes its end`() {
        assertFalse(isReminderEligible(true, start, due, due.minusNanos(1)))
        assertTrue(isReminderEligible(true, start, due, due))
        assertTrue(isReminderEligible(true, start, due, due.plusSeconds(120).minusNanos(1)))
        assertFalse(isReminderEligible(true, start, due, due.plusSeconds(120)))
        assertFalse(isReminderEligible(true, start, due, start))
    }

    @Test
    fun `inactive and late bookings are ineligible`() {
        assertFalse(isReminderEligible(false, start, due, due))
        assertFalse(isReminderEligible(true, start, due.plusNanos(1), due.plusSeconds(1)))
        assertTrue(isReminderEligible(true, start, due.minusNanos(1), due.plusSeconds(1)))
    }
}
