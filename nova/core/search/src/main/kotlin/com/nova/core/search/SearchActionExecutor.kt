package com.nova.core.search

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.search.SearchSummary
import com.nova.core.agent.search.WebSearch
import com.nova.core.agent.web.UrlOpener
import com.nova.core.agent.web.asQueryParameter
import java.io.IOException

/** Looks things up and reads back what was found. */
class SearchActionExecutor(
    private val search: WebSearch,
    /**
     * Where a search goes when there is no API token.
     *
     * Without this, every search command failed on a phone that had never been given an Apify
     * token — which is the state a phone is in until somebody goes and gets one. Opening the
     * browser is what the desktop assistants in the reference documents do, it needs no
     * configuration at all, and a page of results the user can read beats a spoken apology.
     */
    private val urlOpener: UrlOpener? = null,
) : ActionExecutor {

    override val name: String = "search"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.SearchWeb

    override suspend fun execute(action: NovaAction): ActionResult {
        val query = (action as? NovaAction.SearchWeb)?.query
            ?: return ActionResult.Unhandled(action)

        if (!search.isConfigured) {
            return openInBrowser(query)
                // Only when there is no browser either. Saying exactly what is missing is more
                // use than a generic failure.
                ?: ActionResult.Failure("I need an Apify token to search. Add one in Setup.")
        }

        return runCatching { search.search(query) }.fold(
            onSuccess = { ActionResult.Success(SearchSummary.spoken(query, it)) },
            onFailure = { failure ->
                ActionResult.Failure(
                    when (failure) {
                        is SearchNotConfiguredException -> "I need an Apify token to search."
                        // Distinguished because the fixes differ entirely: one is the network,
                        // the other is a token or an account.
                        is IOException -> "I couldn't reach the search service."
                        else -> "That search didn't work."
                    },
                    failure,
                )
            },
        )
    }

    /** Null when there is no opener, or nothing on the device claimed the link. */
    private suspend fun openInBrowser(query: String): ActionResult? {
        val opener = urlOpener ?: return null
        val url = "https://www.google.com/search?q=${query.asQueryParameter()}"

        val opened = runCatching { opener.open(url) }.getOrDefault(false)

        // The query is repeated back rather than the URL. Read aloud a URL is unintelligible,
        // and hearing the query is what tells the user it was understood correctly.
        return if (opened) ActionResult.Success("Here's what I found for $query.") else null
    }
}
