package com.nova.feature.memory

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.memory.Memory
import com.nova.core.agent.memory.spoken

/**
 * Stores and answers from what Nova has been told.
 *
 * Entirely local — no model, no network. This is the part that makes the assistant personal,
 * and it is also the part holding things the user cannot get back, so it never guesses: an
 * unmatched question says so rather than reading out the nearest unrelated fact.
 */
class MemoryActionExecutor(
    private val memory: Memory,
) : ActionExecutor {

    override val name: String = "memory"

    override fun canHandle(action: NovaAction): Boolean = when (action) {
        is NovaAction.Remember,
        is NovaAction.Recall,
        is NovaAction.ForgetMemory,
        NovaAction.RecallAll,
        -> true

        else -> false
    }

    override suspend fun execute(action: NovaAction): ActionResult = when (action) {
        is NovaAction.Remember -> {
            memory.remember(action.subject, action.detail)
            ActionResult.Success("Got it — ${action.subject} is ${action.detail}.")
        }

        is NovaAction.Recall -> memory.recall(action.subject)
            // Answered with its age when the fact is old enough for that to matter. Where you
            // parked is not still true three months later, and only the user can tell.
            ?.let { ActionResult.Success(it.spoken()) }
            ?: ActionResult.Failure("I don't have anything about ${action.subject}.")

        NovaAction.RecallAll -> {
            val entries = memory.all()
            if (entries.isEmpty()) {
                ActionResult.Success("I haven't been told anything yet.")
            } else {
                // Spoken aloud, so a long list is useless. The count tells the user there is
                // more without reciting it.
                val spoken = entries.take(SPOKEN_LIMIT)
                    .joinToString(". ") { "${it.subject} is ${it.detail}" }
                val remainder = entries.size - SPOKEN_LIMIT

                if (remainder > 0) {
                    ActionResult.Success("$spoken. And $remainder more.")
                } else {
                    ActionResult.Success(spoken)
                }
            }
        }

        is NovaAction.ForgetMemory -> memory.forget(action.subject)
            ?.let { ActionResult.Success("Forgotten ${it.subject}.") }
            ?: ActionResult.Failure("I don't have anything about ${action.subject}.")

        else -> ActionResult.Unhandled(action)
    }

    private companion object {
        const val SPOKEN_LIMIT = 5
    }
}
