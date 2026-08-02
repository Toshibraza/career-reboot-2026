package com.nova.core.search

import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.search.SearchResult
import com.nova.core.agent.search.WebSearch
import com.nova.core.agent.web.UrlOpener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchActionExecutorTest {

    private class FakeSearch(
        override val isConfigured: Boolean,
        private val results: List<SearchResult> = emptyList(),
    ) : WebSearch {
        override suspend fun search(query: String, limit: Int) = results
    }

    private class FakeOpener(private val succeeds: Boolean = true) : UrlOpener {
        val opened = mutableListOf<String>()
        override suspend fun open(url: String): Boolean {
            opened += url
            return succeeds
        }
    }

    private fun run(
        search: WebSearch,
        opener: UrlOpener? = null,
        query: String = "train times to Pune",
    ) = runBlocking {
        SearchActionExecutor(search, opener).execute(NovaAction.SearchWeb(query))
    }

    @Test
    fun `with no token the search opens in a browser instead of failing`() {
        // The state every phone is in until somebody goes and gets an Apify token, which until
        // now meant every search command failed outright.
        val opener = FakeOpener()

        val result = run(FakeSearch(isConfigured = false), opener)

        assertTrue(result is ActionResult.Success)
        assertEquals(1, opener.opened.size)
        assertTrue(opener.opened.single(), "train+times+to+Pune" in opener.opened.single())
    }

    @Test
    fun `the query is spoken back, never the URL`() {
        // Read aloud a URL is unintelligible, and hearing the query is what tells the user it
        // was understood correctly.
        val result = run(FakeSearch(isConfigured = false), FakeOpener())

        val spoken = (result as ActionResult.Success).spoken.orEmpty()
        assertTrue(spoken, "train times to Pune" in spoken)
        assertTrue(spoken, "http" !in spoken)
    }

    @Test
    fun `a configured token is still preferred over the browser`() {
        // The API answers out loud, which is the better experience when it is available. The
        // browser is the fallback, not the default.
        val opener = FakeOpener()

        val result = run(
            FakeSearch(isConfigured = true, results = listOf(SearchResult("Times", "1pm.", "u"))),
            opener,
        )

        assertTrue(result is ActionResult.Success)
        assertEquals(emptyList<String>(), opener.opened)
    }

    @Test
    fun `with no token and no browser the missing token is named`() {
        val result = run(FakeSearch(isConfigured = false), FakeOpener(succeeds = false))

        assertTrue((result as ActionResult.Failure).spoken, "Apify" in result.spoken)
    }

    @Test
    fun `with no token and no opener at all the missing token is named`() {
        val result = run(FakeSearch(isConfigured = false), opener = null)

        assertTrue((result as ActionResult.Failure).spoken, "Apify" in result.spoken)
    }

    @Test
    fun `special characters in a query survive the URL`() {
        val opener = FakeOpener()

        run(FakeSearch(isConfigured = false), opener, query = "C# vs Java & Kotlin")

        val url = opener.opened.single()
        // Unescaped, the ampersand would start a new query parameter and the hash would
        // truncate the whole thing at the fragment.
        assertTrue(url, "&" !in url.substringAfter("?q="))
        assertTrue(url, "#" !in url)
    }
}
