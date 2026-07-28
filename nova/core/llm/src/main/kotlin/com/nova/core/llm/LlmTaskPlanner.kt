package com.nova.core.llm

import com.nova.core.agent.screen.ScreenSnapshot
import com.nova.core.agent.task.PlannerDecision
import com.nova.core.agent.task.StepRecord
import com.nova.core.agent.task.TaskPlanner
import java.io.IOException

/**
 * A [TaskPlanner] backed by any [ChatClient] — a cloud API, or a model running on the phone.
 *
 * Holds no provider details of its own: [ChatClient] makes the call, [TaskPrompt] does the
 * wording and the mapping. Swapping a cloud model for an on-device one is a different client
 * passed to this constructor and nothing else.
 */
class LlmTaskPlanner(
    private val client: ChatClient,
) : TaskPlanner {

    override suspend fun next(
        goal: String,
        screen: ScreenSnapshot?,
        history: List<StepRecord>,
    ): PlannerDecision {
        val raw = runCatching {
            client.complete(
                system = TaskPrompt.systemPrompt(),
                user = TaskPrompt.userPrompt(goal, screen, history),
            )
        }.getOrElse { failure ->
            // The step cap should not spend its budget retrying a dead connection, a rejected
            // key, or a model that never loaded, so every failure here blocks.
            return PlannerDecision.Blocked(failure.explain())
        }

        return TaskPrompt.parse(raw)
    }

    /**
     * Says what actually went wrong.
     *
     * These messages are spoken aloud, so they stay short — but they must still distinguish a
     * rejected key from a missing signal from a missing model file. Collapsing every failure
     * into one message hid a real problem the first time this ran against the live API.
     */
    private fun Throwable.explain(): String = when {
        this is RateLimitedException ->
            "I've hit my limit on AI requests. It resets $until."

        this is ModelUnavailableException -> reason

        this is MissingApiKeyException ->
            "I don't have an API key yet. Add one in Nova's settings."

        this is OpenAiHttpException && status == 401 ->
            "My API key was rejected."

        // A 429 means two very different things. Rate limiting clears by waiting; an exhausted
        // quota never does, and telling someone to try again in a moment would send them round
        // a loop that cannot end.
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
}

/**
 * An on-device model could not answer — no model file, too little memory, or a load failure.
 *
 * Carries its own [reason] because these are things a user can act on ("the model isn't
 * installed"), and generic wording would hide that.
 */
class ModelUnavailableException(val reason: String) : IOException(reason)
