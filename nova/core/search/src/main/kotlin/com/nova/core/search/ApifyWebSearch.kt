package com.nova.core.search

import com.nova.core.agent.search.SearchResult
import com.nova.core.agent.search.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** No Apify token configured. Search is optional, so this is not a failure of anything else. */
class SearchNotConfiguredException : IOException("No Apify token")

/**
 * Web search through Apify's RAG Web Browser Actor.
 *
 * Apify's own repository is a set of skills for AI *coding* agents — markdown telling a
 * developer's agent how to drive their CLI. None of it compiles into an Android app, so this
 * calls the same Actors over their REST API instead.
 *
 * `run-sync-get-dataset-items` is used deliberately: it searches, fetches the pages and returns
 * the results in one blocking request. The alternative is start-a-run then poll, which means
 * holding state across a network round trip for a feature the user is waiting on out loud.
 */
class ApifyWebSearch(
    private val token: () -> String?,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val timeoutMillis: Int = 45_000,
) : WebSearch {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val isConfigured: Boolean
        get() = !token().isNullOrBlank()

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val key = token()?.takeIf { it.isNotBlank() } ?: throw SearchNotConfiguredException()

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }

            try {
                connection.outputStream.use { it.write(requestBody(query, limit).toString().toByteArray()) }

                val status = connection.responseCode
                if (status !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException("Apify returned HTTP $status: ${detail.take(MAX_ERROR_DETAIL)}")
                }

                parse(connection.inputStream.bufferedReader().use { it.readText() }, limit)
            } finally {
                connection.disconnect()
            }
        }

    private fun requestBody(query: String, limit: Int) = buildJsonObject {
        put("query", query)
        put("maxResults", limit)
        // Text, not markdown: this is read aloud, and markdown syntax spoken is gibberish.
        put("outputFormats", json.parseToJsonElement("""["text"]"""))
    }

    /**
     * Pulls title, description and url out of whatever shape came back.
     *
     * Deliberately defensive. This is a third party's response format for an Actor that can be
     * updated independently of this app, and several field names have been used across
     * versions — a rename upstream should degrade to fewer results, never crash a voice
     * command.
     */
    internal fun parse(body: String, limit: Int): List<SearchResult> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val items = (root as? JsonArray) ?: return emptyList()

        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val result = item["searchResult"]?.jsonObject ?: item
            val metadata = item["metadata"]?.jsonObject

            val title = result.text("title") ?: metadata?.text("title") ?: return@mapNotNull null
            val snippet = result.text("description")
                ?: metadata?.text("description")
                ?: item.text("text")
                ?: ""
            val url = result.text("url") ?: metadata?.text("url") ?: ""

            SearchResult(title.trim(), snippet.trim(), url)
        }.take(limit)
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val DEFAULT_ENDPOINT =
            "https://api.apify.com/v2/acts/apify~rag-web-browser/run-sync-get-dataset-items"
        const val MAX_ERROR_DETAIL = 300
    }
}
