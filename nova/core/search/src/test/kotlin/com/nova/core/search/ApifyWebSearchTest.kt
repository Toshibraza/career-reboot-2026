package com.nova.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing a third party's response, which can change without warning.
 *
 * The point of these is that an upstream rename should cost results, never a crash — a voice
 * command that throws is far worse than one that finds nothing.
 */
class ApifyWebSearchTest {

    private val search = ApifyWebSearch(token = { "test" })

    @Test
    fun `reads the documented shape`() {
        val body = """
        [
          {
            "searchResult": {
              "title": "Kotlin coroutines",
              "description": "Structured concurrency.",
              "url": "https://kotlinlang.org/coroutines"
            },
            "text": "Long page content here"
          }
        ]
        """.trimIndent()

        val results = search.parse(body, limit = 3)

        assertEquals(1, results.size)
        assertEquals("Kotlin coroutines", results[0].title)
        assertEquals("Structured concurrency.", results[0].snippet)
        assertEquals("https://kotlinlang.org/coroutines", results[0].url)
    }

    @Test
    fun `falls back to metadata when searchResult is absent`() {
        val body = """
        [{"metadata": {"title": "From metadata", "description": "Desc", "url": "https://x.test"}}]
        """.trimIndent()

        assertEquals("From metadata", search.parse(body, 3).single().title)
    }

    @Test
    fun `falls back to page text when there is no description`() {
        val body = """[{"searchResult": {"title": "T", "url": "https://x.test"}, "text": "Body text"}]"""
        assertEquals("Body text", search.parse(body, 3).single().snippet)
    }

    @Test
    fun `an item with no title is skipped rather than read as blank`() {
        val body = """
        [
          {"searchResult": {"description": "orphan snippet"}},
          {"searchResult": {"title": "Real", "description": "d", "url": "u"}}
        ]
        """.trimIndent()

        assertEquals(listOf("Real"), search.parse(body, 3).map { it.title })
    }

    @Test
    fun `malformed json yields nothing instead of throwing`() {
        assertTrue(search.parse("not json", 3).isEmpty())
        assertTrue(search.parse("", 3).isEmpty())
        assertTrue(search.parse("""{"error":"unexpected object"}""", 3).isEmpty())
    }

    @Test
    fun `unknown extra fields are ignored`() {
        // Upstream adding fields must not break parsing.
        val body = """[{"searchResult":{"title":"T","description":"d","url":"u","rank":1},"newField":42}]"""
        assertEquals("T", search.parse(body, 3).single().title)
    }

    @Test
    fun `results are capped at the limit`() {
        val body = (1..10).joinToString(",", "[", "]") {
            """{"searchResult":{"title":"T$it","description":"d","url":"u"}}"""
        }
        assertEquals(3, search.parse(body, 3).size)
    }

    @Test
    fun `missing token is reported as unconfigured`() {
        assertTrue(!ApifyWebSearch(token = { null }).isConfigured)
        assertTrue(!ApifyWebSearch(token = { "  " }).isConfigured)
        assertTrue(ApifyWebSearch(token = { "abc" }).isConfigured)
    }
}
