package com.nova.core.search

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.search.SearchSummary
import com.nova.core.agent.search.WebSearch
import java.io.IOException

/** Looks things up and reads back what was found. */
class SearchActionExecutor(
    private val search: WebSearch,
) : ActionExecutor {

    override val name: String = "search"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.SearchWeb

    override suspend fun execute(action: NovaAction): ActionResult {
        val query = (action as? NovaAction.SearchWeb)?.query
            ?: return ActionResult.Unhandled(action)

        if (!search.isConfigured) {
            // Not a permission and not a crash: search is optional, and saying exactly what is
            // missing is more use than a generic failure.
            return ActionResult.Failure("I need an Apify token to search. Add one in Setup.")
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
}
