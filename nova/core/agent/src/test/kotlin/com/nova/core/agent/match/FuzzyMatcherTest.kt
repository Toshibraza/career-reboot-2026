package com.nova.core.agent.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyMatcherTest {

    /** A realistic slice of an Indian Android phone, short labels included. */
    private val apps = listOf(
        "YouTube", "WhatsApp", "X", "Chrome", "Settings", "Camera",
        "Google Home", "Phone", "Mi Video", "Gmail", "Paytm",
    )

    private fun app(query: String): String? = FuzzyMatcher.best(query, apps) { it }

    @Test
    fun `matches exact and cased labels`() {
        assertEquals("YouTube", app("youtube"))
        assertEquals("Chrome", app("Chrome"))
    }

    @Test
    fun `collapses transcript spacing`() {
        assertEquals("WhatsApp", app("whats app"))
        assertEquals("YouTube", app("you tube"))
        assertEquals("Google Home", app("google home"))
    }

    @Test
    fun `tolerates near-miss transcription`() {
        assertEquals("Chrome", app("chrom"))
        assertEquals("WhatsApp", app("whatsap"))
    }

    @Test
    fun `single-letter labels only match exactly`() {
        // Regression: "X" is a substring of nearly every query, so a substring rule handed
        // gibberish straight to Twitter. Verified on device — "open zqxwv" opened X.
        assertNull(app("zqxwv"))
        assertEquals("X", app("x"))
    }

    @Test
    fun `gibberish resolves to nothing`() {
        assertNull(app("qwertyasdf"))
        assertNull(app("blahblahblah"))
    }

    @Test
    fun `short labels do not outscore the real match`() {
        assertEquals("Mi Video", app("mi video"))
        assertEquals("Paytm", app("paytm"))
    }

    // --- On-screen labels, the accessibility use case ---------------------------------

    @Test
    fun `matches on-screen button labels`() {
        val buttons = listOf("Send", "Cancel", "Save draft", "Attach file", "OK")
        assertEquals("Send", FuzzyMatcher.best("send", buttons) { it })
        assertEquals("Save draft", FuzzyMatcher.best("save draft", buttons) { it })
        assertEquals("Attach file", FuzzyMatcher.best("attach", buttons) { it })
    }

    @Test
    fun `declines when no button is close`() {
        val buttons = listOf("Send", "Cancel", "OK")
        assertNull(FuzzyMatcher.best("purchase", buttons) { it })
    }
}
