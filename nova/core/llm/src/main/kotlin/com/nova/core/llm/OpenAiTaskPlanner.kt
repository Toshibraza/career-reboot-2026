package com.nova.core.llm

import com.nova.core.agent.screen.ScreenSnapshot
import com.nova.core.agent.task.PlannerDecision
import com.nova.core.agent.task.StepRecord
import com.nova.core.agent.task.TaskPlanner

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
            // Network trouble is not the user's problem to diagnose, and the runtime's step
            // cap should not spend its budget retrying a dead connection.
            return PlannerDecision.Blocked(
                if (failure is java.io.IOException) {
                    "I couldn't reach the network to work that out."
                } else {
                    "I couldn't work that out."
                },
            )
        }

        return TaskPrompt.parse(raw)
    }
}
