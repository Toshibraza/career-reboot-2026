package com.nova.core.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(millis: Long) = Freshness.describe(now - millis, now)

    @Test
    fun `recent facts are said without a qualifier`() {
        // "Your parking spot is B4 — I noted that 20 minutes ago" is noise. It is obviously
        // still true, and the extra clause costs a second of speech on every recall.
        assertNull(ago(0))
        assertNull(ago(20 * minute))
        assertNull(ago(23 * hour))
    }

    @Test
    fun `a day old is where the age starts mattering`() {
        assertEquals("yesterday", ago(25 * hour))
        assertEquals("yesterday", ago(2 * day - minute))
    }

    @Test
    fun `days weeks months and years each read naturally`() {
        assertEquals("3 days ago", ago(3 * day))
        assertEquals("last week", ago(8 * day))
        assertEquals("3 weeks ago", ago(23 * day))
        assertEquals("last month", ago(40 * day))
        assertEquals("4 months ago", ago(130 * day))
        assertEquals("over a year ago", ago(400 * day))
        assertEquals("3 years ago", ago(1100 * day))
    }

    @Test
    fun `a timestamp from the future says nothing rather than something absurd`() {
        // Clock changes and timezone shifts happen. "I noted that -1 days ago" would be worse
        // than the plain sentence.
        assertNull(Freshness.describe(now + day, now))
    }

    @Test
    fun `a missing timestamp says nothing rather than 1970`() {
        // An unset or unmigrated row reads as an epoch value. Saying "I noted that 56 years
        // ago" would answer a question the data cannot answer.
        assertNull(Freshness.describe(0L, now))
        assertNull(Freshness.describe(42L, now))
    }

    @Test
    fun `a fresh entry is spoken as a plain sentence`() {
        val entry = MemoryEntry("my parking spot", "B4", updatedAt = now - hour)

        assertEquals("my parking spot is B4.", entry.spoken(now))
    }

    @Test
    fun `a stale entry carries its age`() {
        val entry = MemoryEntry("my parking spot", "B4", updatedAt = now - 90 * day)

        // The point of the whole file: this is very likely wrong now, and the user is the only
        // one who can tell.
        assertEquals("my parking spot is B4 — I noted that 3 months ago.", entry.spoken(now))
    }
}
