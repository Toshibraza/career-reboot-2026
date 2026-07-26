package com.nova.core.agent.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeOfDayTest {

    private fun parse(text: String) = TimeOfDay.parse(text)

    @Test
    fun `reads explicit am and pm`() {
        assertEquals(TimeOfDay(8, 0), parse("remind me at 8 am"))
        assertEquals(TimeOfDay(20, 0), parse("remind me at 8 pm"))
        assertEquals(TimeOfDay(20, 30), parse("every day at 8:30 pm"))
    }

    @Test
    fun `handles the midnight and noon edges`() {
        // 12 am is midnight and 12 pm is noon — the one place the twelve-hour clock is a trap.
        assertEquals(TimeOfDay(0, 0), parse("at 12 am"))
        assertEquals(TimeOfDay(12, 0), parse("at 12 pm"))
    }

    @Test
    fun `reads 24-hour times`() {
        assertEquals(TimeOfDay(18, 0), parse("every day at 18:00"))
        assertEquals(TimeOfDay(6, 45), parse("at 06:45"))
    }

    @Test
    fun `a bare hour is resolved by the surrounding words`() {
        assertEquals(TimeOfDay(8, 0), parse("every morning at 8"))
        assertEquals(TimeOfDay(20, 0), parse("remind me at 8 tonight"))
        assertEquals(TimeOfDay(21, 0), parse("at 9 in the evening"))
    }

    @Test
    fun `a bare hour with no hint picks the likely one`() {
        // "At 3" is almost never three in the morning; "at 9" almost always is nine in the
        // morning. Guessing wrong by twelve hours is the worst failure a reminder can have,
        // so the guess follows how people actually speak.
        assertEquals(TimeOfDay(15, 0), parse("remind me at 3"))
        assertEquals(TimeOfDay(9, 0), parse("remind me at 9"))
    }

    @Test
    fun `rejects impossible times`() {
        assertNull(parse("at 25:00"))
        assertNull(parse("at 13 pm"))
        assertNull(parse("at 8:75 am"))
    }

    @Test
    fun `returns null when there is no time at all`() {
        assertNull(parse("open youtube"))
        assertNull(parse("remember my parking spot is B2"))
    }

    @Test
    fun `spoken form reads naturally`() {
        assertEquals("8 am", TimeOfDay(8, 0).spoken())
        assertEquals("8:30 pm", TimeOfDay(20, 30).spoken())
        assertEquals("12 am", TimeOfDay(0, 0).spoken())
        assertEquals("12 pm", TimeOfDay(12, 0).spoken())
    }
}
