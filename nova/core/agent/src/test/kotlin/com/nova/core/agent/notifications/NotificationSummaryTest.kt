package com.nova.core.agent.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSummaryTest {

    private fun note(app: String, title: String = "", text: String = "") =
        NovaNotification(app, title, text, postedAt = 0)

    @Test
    fun `nothing new is stated, not silence`() {
        assertEquals("Nothing new.", emptyList<NovaNotification>().summarise())
    }

    @Test
    fun `a single notification is read without a count`() {
        // "You have 1. WhatsApp..." is worse English than just saying it.
        assertEquals(
            "WhatsApp, Amit: see you at six",
            listOf(note("WhatsApp", "Amit", "see you at six")).summarise(),
        )
    }

    @Test
    fun `several are counted then read`() {
        val summary = listOf(
            note("WhatsApp", "Amit", "see you at six"),
            note("Gmail", "HR", "offer letter"),
        ).summarise()

        assertEquals("You have 2. WhatsApp, Amit: see you at six. Gmail, HR: offer letter", summary)
    }

    @Test
    fun `a long list is capped and the rest counted`() {
        // Reading twenty notifications aloud is useless; the count says there is more.
        val many = (1..8).map { note("App$it", "Title$it", "Body$it") }
        val summary = many.summarise(limit = 3)

        assertTrue(summary.startsWith("You have 8."))
        assertTrue("And 5 more." in summary)
        assertTrue("App4" !in summary)
    }

    @Test
    fun `long bodies are clipped at a word boundary`() {
        // Verbatim shape from a real Outlook notification: three hundred characters of
        // marketing prose. Read out in full it buries whatever actually mattered.
        val wordy = note(
            "Outlook",
            "Indeed",
            "Hi T, Your background in embedded systems and C++ could be a great match for " +
                "this Software Engineer position at ATHECONS. If you're looking to leverage " +
                "your skills in a hybrid remote role, you can apply now or view the j",
        )

        val spoken = listOf(wordy).summarise()
        assertTrue(spoken.length < 180)
        assertTrue(spoken.endsWith("…"))
        // Cut on a word, not mid-syllable.
        assertTrue("embedde…" !in spoken)
    }

    @Test
    fun `newlines in a body do not break the spoken line`() {
        val multiline = note("Telegram", "Tech", "price is 2.5$ per mail\n\nminimum bulk=10")
        assertEquals(
            "Telegram, Tech: price is 2.5$ per mail minimum bulk=10",
            listOf(multiline).summarise(),
        )
    }

    @Test
    fun `missing fields do not produce stray punctuation`() {
        assertEquals("Clock", listOf(note("Clock")).summarise())
        assertEquals("Clock: alarm set", listOf(note("Clock", text = "alarm set")).summarise())
        assertEquals("Clock, Alarm", listOf(note("Clock", title = "Alarm")).summarise())
    }
}
