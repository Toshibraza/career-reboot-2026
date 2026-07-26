package com.nova.core.agent.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSummaryTest {

    @Test
    fun `reads recognised lines back in order`() {
        assertEquals(
            "Electricity Bill. Amount due 1,240. Due 15 August",
            OcrSummary.summarise(listOf("Electricity Bill", "Amount due 1,240", "Due 15 August")),
        )
    }

    @Test
    fun `drops noise that is not worth speaking`() {
        // OCR routinely returns stray glyphs from icons and borders. Reading "|" and "-" aloud
        // between real lines makes the useful text harder to follow, not easier.
        assertEquals(
            "Total 500",
            OcrSummary.summarise(listOf("|", "-", "•", "Total 500", "")),
        )
    }

    @Test
    fun `a page of text is capped and the rest counted`() {
        val page = (1..30).map { "Line number $it" }
        val summary = OcrSummary.summarise(page, limit = 5)

        assertTrue(summary.startsWith("Line number 1."))
        assertTrue("And 25 more lines." in summary)
        assertTrue("Line number 6" !in summary)
    }

    @Test
    fun `no text at all is stated plainly`() {
        assertEquals("I couldn't find any text.", OcrSummary.summarise(emptyList()))
        assertEquals("I couldn't find any text.", OcrSummary.summarise(listOf("", " ", "|")))
    }
}
