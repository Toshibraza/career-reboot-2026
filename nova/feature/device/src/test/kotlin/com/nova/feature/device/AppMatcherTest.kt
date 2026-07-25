package com.nova.feature.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatcherTest {

    /** A realistic slice of an Indian Android phone, short labels included. */
    private val installed = listOf(
        "YouTube", "WhatsApp", "X", "Chrome", "Settings", "Camera",
        "Google Home", "Phone", "Mi Video", "Gmail", "Paytm",
    )

    private fun resolve(query: String): String? =
        AppMatcher.best(query, installed) { it }

    @Test
    fun `matches exact and cased labels`() {
        assertEquals("YouTube", resolve("youtube"))
        assertEquals("Chrome", resolve("Chrome"))
    }

    @Test
    fun `collapses transcript spacing`() {
        assertEquals("WhatsApp", resolve("whats app"))
        assertEquals("YouTube", resolve("you tube"))
        assertEquals("Google Home", resolve("google home"))
    }

    @Test
    fun `tolerates near-miss transcription`() {
        assertEquals("Chrome", resolve("chrom"))
        assertEquals("WhatsApp", resolve("whatsap"))
    }

    @Test
    fun `single-letter apps only match exactly`() {
        // Regression: "X" is a substring of nearly every query, so a substring rule handed
        // gibberish straight to Twitter. Verified on device — "open zqxwv" opened X.
        assertNull(resolve("zqxwv"))
        assertEquals("X", resolve("x"))
    }

    @Test
    fun `gibberish resolves to nothing`() {
        assertNull(resolve("qwertyasdf"))
        assertNull(resolve("blahblahblah"))
    }

    @Test
    fun `short labels do not outscore the real match`() {
        assertEquals("Mi Video", resolve("mi video"))
        assertEquals("Paytm", resolve("paytm"))
    }
}
