package com.nova.core.agent.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSummaryTest {

    private fun result(title: String, snippet: String = "", url: String = "https://x.test") =
        SearchResult(title, snippet, url)

    @Test
    fun `nothing found is stated with the query`() {
        // Naming the query back is what lets the user hear a mis-transcription.
        assertEquals(
            "I couldn't find anything for quantum widgets.",
            SearchSummary.spoken("quantum widgets", emptyList()),
        )
    }

    @Test
    fun `reads title and snippet`() {
        val spoken = SearchSummary.spoken(
            "kotlin coroutines",
            listOf(result("Coroutines guide", "Structured concurrency in Kotlin.")),
        )
        assertEquals("Coroutines guide. Structured concurrency in Kotlin.", spoken)
    }

    @Test
    fun `urls are never spoken`() {
        // A URL read aloud is unintelligible; it belongs on screen, not in the ear.
        val spoken = SearchSummary.spoken(
            "example",
            listOf(result("Title", "Snippet", "https://example.com/a/very/long/path")),
        )
        assertTrue("https" !in spoken)
        assertTrue("example.com" !in spoken)
    }

    @Test
    fun `whitespace in snippets is flattened`() {
        val messy = result("Title", "line one\n\n   line two\ttabbed")
        assertEquals("Title. line one line two tabbed", SearchSummary.spoken("q", listOf(messy)))
    }

    @Test
    fun `long snippets are cut`() {
        val long = result("Title", "word ".repeat(200))
        val spoken = SearchSummary.spoken("q", listOf(long))

        assertTrue(spoken.length < 220)
        assertTrue(spoken.endsWith("…"))
    }

    @Test
    fun `only the first couple of results are read`() {
        // Three results spoken is already long; a page of them is unusable.
        val many = (1..5).map { result("Result $it", "Snippet $it") }
        val spoken = SearchSummary.spoken("q", many, limit = 2)

        assertTrue("Result 1" in spoken)
        assertTrue("Result 2" in spoken)
        assertTrue("Result 3" !in spoken)
    }

    @Test
    fun `a result with no snippet still reads`() {
        assertEquals("Just a title", SearchSummary.spoken("q", listOf(result("Just a title"))))
    }
}
