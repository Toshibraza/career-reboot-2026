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
                schema = TaskPrompt.responseSchema(),
            )
        }.getOrElse { failure ->
            // The step cap should not spend its budget retrying a dead connection, a rejected
            // key, or a model that never loaded, so every failure here blocks.
            return PlannerDecision.Blocked(failure.spokenLlmFailure())
        }

        return TaskPrompt.parse(raw)
    }

}

/**
 * An on-device model could not answer — no model file, too little memory, or a load failure.
 *
 * Carries its own [reason] because these are things a user can act on ("the model isn't
 * installed"), and generic wording would hide that.
 */
class ModelUnavailableException(val reason: String) : IOException(reason)
