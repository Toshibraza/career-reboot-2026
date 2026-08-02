package com.nova.core.agent.help

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction

/**
 * Answers "what can you do?".
 *
 * Pure, like [com.nova.core.agent.SpeakActionExecutor] — the answer is a string, and the one
 * place that speaks already knows how to say it.
 */
class CapabilitiesActionExecutor : ActionExecutor {

    override val name: String = "capabilities"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.ListCapabilities

    override suspend fun execute(action: NovaAction): ActionResult =
        if (action is NovaAction.ListCapabilities) {
            ActionResult.Success(Capabilities.spoken())
        } else {
            ActionResult.Unhandled(action)
        }
}
