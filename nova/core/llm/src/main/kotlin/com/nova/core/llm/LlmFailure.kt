package com.nova.core.llm

import java.io.IOException

/**
 * Says what actually went wrong with a model call.
 *
 * Shared by everything that talks to a model. There are two callers now — planning a task and
 * holding a conversation — and one copy each would drift: the day a new failure mode is
 * diagnosed, only whichever path someone happened to be debugging would learn to report it.
 *
 * These are spoken aloud, so they stay short, but they must still distinguish a rejected key
 * from a missing signal from a missing model file. Collapsing every failure into one message
 * hid a real problem the first time this ran against the live API.
 */
fun Throwable.spokenLlmFailure(): String = when {
    this is RateLimitedException ->
        "I've hit my limit on AI requests. It resets $until."

    this is ModelUnavailableException -> reason

    this is MissingApiKeyException ->
        "I don't have an API key yet. Add one in Raza's settings."

    this is OpenAiHttpException && status == 401 ->
        "My API key was rejected."

    // A 429 means two very different things. Rate limiting clears by waiting; an exhausted
    // quota never does, and telling someone to try again in a moment would send them round a
    // loop that cannot end.
    this is OpenAiHttpException && status == 429 && "insufficient_quota" in detail ->
        "My OpenAI account is out of credit."

    this is OpenAiHttpException && status == 429 ->
        "I'm being rate limited. Try again in a moment."

    this is OpenAiHttpException && status == 400 ->
        "The service rejected my request."

    this is OpenAiHttpException && status >= 500 ->
        "The service is having trouble right now."

    this is OpenAiHttpException ->
        "The service returned an error."

    this is IOException ->
        "I couldn't reach the network to work that out."

    else -> "I couldn't work that out."
}
