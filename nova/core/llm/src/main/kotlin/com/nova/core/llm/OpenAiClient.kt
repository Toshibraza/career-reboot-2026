package com.nova.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A structured chat completion. */
interface ChatClient {

    /**
     * Returns the model's reply as a raw string.
     *
     * [schema] constrains the reply's shape where the transport supports it. Null means free
     * text. The schema travels with the request rather than living in the client, because the
     * same client serves callers with different expectations — the task planner needs one JSON
     * object, conversation needs a spoken sentence — and a transport that hardcodes one
     * caller's schema silently corrupts the other's replies.
     */
    suspend fun complete(system: String, user: String, schema: ResponseSchema? = null): String
}

/** A JSON Schema the reply must conform to, with the name the API labels it by. */
data class ResponseSchema(val name: String, val json: String)

/**
 * The request reached the API and came back an error.
 *
 * Distinct from a plain [IOException] on purpose: "your key is wrong" and "the phone has no
 * signal" need different answers, and collapsing both into "couldn't reach the network" makes
 * the real problem undiagnosable — which is exactly what happened the first time this ran.
 */
class OpenAiHttpException(
    val status: Int,
    /** API error text. Never contains the key; safe to log. */
    val detail: String,
) : IOException("OpenAI returned HTTP $status: $detail")

/** No key is configured, so there is nothing to send. */
class MissingApiKeyException : IOException("No OpenAI API key configured")

/**
 * Minimal OpenAI chat client over [HttpURLConnection].
 *
 * Hand-rolled rather than pulling in an SDK: this makes exactly one kind of request, and a
 * single dependency-free file is easier to audit than a transitive HTTP stack — which matters
 * when the thing being sent is a description of the user's screen.
 */
class OpenAiClient(
    /**
     * Resolved per call, not captured once.
     *
     * The key can change while the app is running — the user pastes a new one after rotating
     * it — and a client holding a stale copy would keep failing until the process restarted.
     */
    private val apiKey: () -> String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val timeoutMillis: Int = 30_000,
) : ChatClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, user: String, schema: ResponseSchema?): String =
        withContext(Dispatchers.IO) {
            val key = apiKey().trim()
            if (key.isEmpty()) throw MissingApiKeyException()

            val body = requestBody(system, user, schema).toString()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }

            try {
                connection.outputStream.use { it.write(body.toByteArray()) }

                val status = connection.responseCode
                if (status !in 200..299) {
                    val error = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                    throw OpenAiHttpException(status, error.take(MAX_ERROR_DETAIL))
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                extractContent(response)
            } finally {
                connection.disconnect()
            }
        }

    private fun requestBody(system: String, user: String, schema: ResponseSchema?): JsonObject =
        buildJsonObject {
            put("model", model)
            // Planning a phone action is not a place for creative variation.
            put("temperature", 0)
            if (schema != null) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", schema.name)
                        put("strict", true)
                        put("schema", json.parseToJsonElement(schema.json))
                    }
                }
            }
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        },
                    )
                },
            )
        }

    private fun extractContent(response: String): String =
        json.parseToJsonElement(response)
            .jsonObject["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            ?: throw IOException("OpenAI response contained no message content")

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"

        /** Cheap and quick, which matters when a single task makes up to eight calls. */
        const val DEFAULT_MODEL = "gpt-4o-mini"

        /** Enough of the API error to diagnose, short enough not to flood a log. */
        private const val MAX_ERROR_DETAIL = 400
    }
}
