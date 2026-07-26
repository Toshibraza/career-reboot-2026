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

/** A structured chat completion, with the reply shape enforced by a JSON schema. */
interface ChatClient {

    /** Returns the raw JSON string the model produced. */
    suspend fun complete(system: String, user: String): String
}

/**
 * Minimal OpenAI chat client over [HttpURLConnection].
 *
 * Hand-rolled rather than pulling in an SDK: this makes exactly one kind of request, and a
 * single dependency-free file is easier to audit than a transitive HTTP stack — which matters
 * when the thing being sent is a description of the user's screen.
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val timeoutMillis: Int = 30_000,
) : ChatClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val body = requestBody(system, user).toString()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            try {
                connection.outputStream.use { it.write(body.toByteArray()) }

                val status = connection.responseCode
                if (status !in 200..299) {
                    // Deliberately does not include the response body: error payloads can echo
                    // request content, and this message may end up in a log.
                    throw IOException("OpenAI request failed with HTTP $status")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                extractContent(response)
            } finally {
                connection.disconnect()
            }
        }

    private fun requestBody(system: String, user: String): JsonObject = buildJsonObject {
        put("model", model)
        // Planning a phone action is not a place for creative variation.
        put("temperature", 0)
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "planner_decision")
                put("strict", true)
                put("schema", json.parseToJsonElement(TaskPrompt.RESPONSE_SCHEMA))
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
    }
}
