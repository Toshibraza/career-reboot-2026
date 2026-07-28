package com.nova.core.agent.search

/** One result worth reading back. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
)

/**
 * Looks something up on the web.
 *
 * Implemented against Apify's REST API in `:feature:websearch`. The interface stays here so the
 * provider is replaceable — a different search backend is a different implementation and
 * nothing else changes, exactly as with [com.nova.core.agent.task.TaskPlanner].
 */
interface WebSearch {

    /** False when no token is configured — search is optional and everything else works. */
    val isConfigured: Boolean

    suspend fun search(query: String, limit: Int = 3): List<SearchResult>
}

/**
 * Turns results into something worth hearing.
 *
 * Pure, so the part that decides what a user actually hears is testable without a network. Read
 * aloud, three results is already a lot — a page of them is unusable, and URLs are unspeakable,
 * so they are kept for the screen and left out of the spoken line.
 */
object SearchSummary {

    private const val MAX_SNIPPET = 160

    fun spoken(query: String, results: List<SearchResult>, limit: Int = 2): String {
        if (results.isEmpty()) return "I couldn't find anything for $query."

        return results.take(limit).joinToString(". ") { result ->
            val snippet = result.snippet.flatten().take(MAX_SNIPPET).trimEnd().let {
                if (it.length < result.snippet.flatten().length) "$it…" else it
            }
            if (snippet.isBlank()) result.title.flatten() else "${result.title.flatten()}. $snippet"
        }
    }

    /** Search snippets arrive full of newlines and runs of spaces; spoken, those are noise. */
    private fun String.flatten(): String = replace(Regex("\\s+"), " ").trim()
}
