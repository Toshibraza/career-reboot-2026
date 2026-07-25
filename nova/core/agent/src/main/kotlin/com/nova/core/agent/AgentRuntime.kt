package com.nova.core.agent

/**
 * The loop: utterance in, plan, execute each action, one spoken line out.
 *
 * Holds no Android types, so it can be driven from a unit test, a foreground service, or later
 * a desktop client.
 */
class AgentRuntime(
    private val intentEngine: IntentEngine,
    private val executors: List<ActionExecutor>,
    private val contextProvider: suspend () -> AgentContext = { AgentContext() },
) {

    suspend fun handle(utterance: String): AgentResponse {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) {
            val plan = Plan.unsupported(utterance, "Empty utterance.")
            return AgentResponse(plan, emptyList(), "I didn't catch that.")
        }

        val plan = intentEngine.plan(trimmed, contextProvider())
        val results = plan.actions.map { action ->
            val executor = executors.firstOrNull { it.canHandle(action) }
            if (executor == null) {
                ActionResult.Unhandled(action)
            } else {
                runCatching { executor.execute(action) }
                    .getOrElse { ActionResult.Failure("That didn't work.", it) }
            }
        }

        return AgentResponse(plan, results, summarise(plan, results))
    }

    /**
     * Collapses per-action results into one utterance. Failures win over successes — the user
     * needs to hear what broke, not a chirpy confirmation followed by a silent no-op.
     */
    private fun summarise(plan: Plan, results: List<ActionResult>): String {
        results.firstNotNullOfOrNull { it as? ActionResult.NeedsPermission }?.let { return it.spoken }
        results.firstNotNullOfOrNull { it as? ActionResult.Failure }?.let { return it.spoken }

        results.firstNotNullOfOrNull { it as? ActionResult.Unhandled }?.let { unhandled ->
            val action = unhandled.action
            return if (action is NovaAction.Unsupported) {
                "I can't do that yet."
            } else {
                "I understood, but nothing on this device can do that yet."
            }
        }

        val spoken = results.filterIsInstance<ActionResult.Success>().mapNotNull { it.spoken }
        return when {
            spoken.isNotEmpty() -> spoken.joinToString(". ")
            plan.acknowledgement != null -> plan.acknowledgement
            else -> "Done."
        }
    }
}
