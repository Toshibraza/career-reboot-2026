package com.nova.core.agent

/**
 * Turns natural language into a [Plan].
 *
 * Phase 1 ships [com.nova.core.agent.rules.RuleIntentEngine]. An LLM-backed engine (on-device
 * Gemma, or the Claude API) implements this same interface and drops in at the composition root
 * with no other change. A hybrid engine that tries rules first and falls back to an LLM is just
 * a third implementation that delegates — see [FallbackIntentEngine].
 */
interface IntentEngine {

    /** Short identifier used in logs and the debug transcript. */
    val name: String

    suspend fun plan(utterance: String, context: AgentContext): Plan
}

/**
 * Tries each engine in order and returns the first confident plan.
 *
 * This is how rule-first-then-LLM works: instant offline handling for the ~40 commands that
 * matter daily, model inference only for the long tail.
 */
class FallbackIntentEngine(
    private val engines: List<IntentEngine>,
    private val confidenceThreshold: Float = 0.5f,
) : IntentEngine {

    override val name: String = engines.joinToString("+") { it.name }

    override suspend fun plan(utterance: String, context: AgentContext): Plan {
        var last: Plan? = null
        for (engine in engines) {
            val plan = engine.plan(utterance, context)
            if (plan.confidence >= confidenceThreshold && plan.actions.none { it is NovaAction.Unsupported }) {
                return plan
            }
            last = plan
        }
        return last ?: Plan.unsupported(utterance, "No intent engine was configured.")
    }
}
