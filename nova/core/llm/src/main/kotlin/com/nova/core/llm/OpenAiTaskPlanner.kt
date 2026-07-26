package com.nova.core.llm

import com.nova.core.agent.screen.ScreenSnapshot
import com.nova.core.agent.task.PlannerDecision
import com.nova.core.agent.task.StepRecord
import com.nova.core.agent.task.TaskPlanner
import java.io.IOException

/**
 * A [TaskPlanner] backed by a chat model.
 *
 * Holds no HTTP or prompt logic of its own — [ChatClient] does the call, [TaskPrompt] does the
 * wording and the mapping — so a test can drive the whole decision path with a fake client.
 */
class OpenAiTaskPlanner(
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
            // The step cap should not spend its budget retrying a dead connection or a
            // rejected key, so every failure here blocks rather than returning an action.
            return PlannerDecision.Blocked(failure.explain())
        }

        return TaskPrompt.parse(raw)
    }

    /**
     * Says what actually went wrong.
     *
     * These messages are spoken aloud, so they stay short — but they must still distinguish a
     * rejected key from a missing signal. Collapsing every failure into "couldn't reach the
     * network" hid a real problem the first time this ran against the live API.
     */
    private fun Throwable.explain(): String = when {
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
