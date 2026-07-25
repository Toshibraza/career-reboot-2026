package com.nova.core.agent

/**
 * Handles [NovaAction.Speak] by passing the text back out as the spoken result.
 *
 * It does not own a text-to-speech engine on purpose. Exactly one place in the app speaks —
 * whatever collects [AgentResponse.spoken] — so answers can't be said twice, and the runtime
 * stays free of platform dependencies.
 */
class SpeakActionExecutor : ActionExecutor {

    override val name: String = "speak"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.Speak

    override suspend fun execute(action: NovaAction): ActionResult =
        if (action is NovaAction.Speak) {
            ActionResult.Success(action.text)
        } else {
            ActionResult.Unhandled(action)
        }
}
