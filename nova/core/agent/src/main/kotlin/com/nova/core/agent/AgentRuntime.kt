package com.nova.core.agent

import com.nova.core.agent.guard.ActionGuard
import com.nova.core.agent.guard.ActionOrigin
import com.nova.core.agent.guard.GuardDecision
import com.nova.core.agent.screen.ScreenSnapshot
import com.nova.core.agent.task.PlannerDecision
import com.nova.core.agent.task.StepRecord
import com.nova.core.agent.task.TaskPlanner

/**
 * The loop: utterance in, plan, execute, one spoken line out.
 *
 * Two modes. Anything the [IntentEngine] recognises is executed directly — one utterance, one
 * plan, done, and no model involved. Anything it cannot parse escalates to the [TaskPlanner],
 * which drives an observe-act loop against the live screen.
 *
 * Holds no Android types, so it can be driven from a unit test, a foreground service, or later
 * a desktop client.
 */
class AgentRuntime(
    private val intentEngine: IntentEngine,
    private val executors: List<ActionExecutor>,
    private val contextProvider: suspend () -> AgentContext = { AgentContext() },
    /** Absent means unrecognised commands are simply declined, as in Phase 1. */
    private val taskPlanner: TaskPlanner? = null,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    /**
     * Vets planner-chosen actions only. Not optional, and deliberately not a constructor
     * parameter anyone is likely to pass `null` to.
     */
    private val guard: ActionGuard = ActionGuard(),
) {

    suspend fun handle(utterance: String): AgentResponse {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) {
            val plan = Plan.unsupported(utterance, "Empty utterance.")
            return AgentResponse(plan, emptyList(), "I didn't catch that.")
        }

        val context = contextProvider()
        val plan = intentEngine.plan(trimmed, context)

        if (taskPlanner != null && plan.actions.any { it is NovaAction.Unsupported }) {
            return runTask(trimmed, context)
        }

        val results = plan.actions.map { execute(it) }

        // "What is my parking spot" and "what is the capital of France" are the same sentence
        // to a rule engine, and only memory can tell them apart — by knowing the answer or not.
        // So memory is asked first and conversation picks up what it does not hold, which
        // keeps stored facts authoritative without making the user phrase questions two ways.
        if (plan.actions.singleOrNull() is NovaAction.Recall &&
            results.singleOrNull() is ActionResult.Failure &&
            canConverse()
        ) {
            return converse(trimmed)
        }

        return AgentResponse(plan, results, summarise(plan, results))
    }

    private fun canConverse(): Boolean =
        executors.any { it.canHandle(NovaAction.Converse("")) }

    private suspend fun converse(utterance: String): AgentResponse {
        val action = NovaAction.Converse(utterance)
        val result = execute(action)
        val plan = Plan(utterance = utterance, actions = listOf(action), confidence = 1f)
        return AgentResponse(plan, listOf(result), summarise(plan, listOf(result)))
    }

    /**
     * Works toward [goal] one step at a time, looking at the screen between each.
     *
     * The step cap is the backstop that matters: a planner that misreads a screen can otherwise
     * tap forever, and every step here is a real touch on someone's phone.
     */
    private suspend fun runTask(goal: String, context: AgentContext): AgentResponse {
        val planner = taskPlanner ?: return AgentResponse(
            Plan.unsupported(goal, "No planner configured."),
            emptyList(),
            "I can't do that yet.",
        )

        val history = mutableListOf<StepRecord>()
        val executed = mutableListOf<NovaAction>()
        val results = mutableListOf<ActionResult>()

        var previousScreen: ScreenSnapshot? = null
        var lastAction: NovaAction? = null
        var stalls = 0

        repeat(maxSteps) {
            val screen = runCatching { context.screen() }.getOrNull()

            val decision = runCatching { planner.next(goal, screen, history) }
                .getOrElse { return finish(goal, executed, results, "I couldn't work that out.") }

            // A planner that repeats an action while the screen is unchanged has stopped
            // making progress, whether or not it realises. Both on-device models failed
            // exactly this way — choosing "open Settings" eight times with Settings already
            // open. Detecting it here works regardless of which model is behind the planner.
            // Two nulls count as unchanged on purpose. With no screen access there is no
            // evidence of progress at all, which makes a repeat more suspicious rather than
            // less — and without the guard the loop would burn every step at ~19 seconds each
            // before admitting it got nowhere.
            if (decision is PlannerDecision.Act &&
                decision.action == lastAction &&
                screen == previousScreen
            ) {
                stalls++
                history += StepRecord(
                    action = decision.action,
                    outcome = "already done, and the screen did not change — try something else",
                    succeeded = false,
                )

                if (stalls >= MAX_STALLS) {
                    return finish(
                        goal,
                        executed,
                        results,
                        "I got stuck repeating the same step, so I stopped.",
                    )
                }
                // Deliberately not executed: doing it again would change nothing and could
                // press something twice that should only be pressed once.
                return@repeat
            }

            previousScreen = screen

            when (decision) {
                is PlannerDecision.Finished ->
                    return finish(goal, executed, results, decision.spoken)

                is PlannerDecision.Blocked ->
                    return finish(goal, executed, results, decision.spoken)

                is PlannerDecision.Act -> {
                    // Checked before executing, never after. The whole task stops rather than
                    // recording a failed step, because the planner reads that history and a
                    // refusal in it is an invitation to look for a synonym — "Proceed" instead
                    // of "Pay". A boundary the model can negotiate with is not a boundary.
                    val verdict = guard.check(decision.action, screen, ActionOrigin.PLANNER)
                    if (verdict is GuardDecision.Refuse) {
                        return finish(goal, executed, results, verdict.spoken)
                    }

                    val result = execute(decision.action)
                    executed += decision.action
                    results += result
                    lastAction = decision.action
                    history += StepRecord(
                        action = decision.action,
                        outcome = result.describe(),
                        succeeded = result is ActionResult.Success,
                    )

                    // A missing permission will not fix itself by trying again, and the user
                    // needs to hear about it now rather than after eight wasted steps.
                    if (result is ActionResult.NeedsPermission) {
                        return finish(goal, executed, results, result.spoken)
                    }
                }
            }
        }

        return finish(goal, executed, results, "I got part of the way but couldn't finish that.")
    }

    private fun finish(
        goal: String,
        executed: List<NovaAction>,
        results: List<ActionResult>,
        spoken: String,
    ) = AgentResponse(
        plan = Plan(utterance = goal, actions = executed.toList(), confidence = 1f),
        results = results.toList(),
        spoken = spoken,
    )

    private suspend fun execute(action: NovaAction): ActionResult {
        val executor = executors.firstOrNull { it.canHandle(action) }
            ?: return ActionResult.Unhandled(action)

        return runCatching { executor.execute(action) }
            .getOrElse { ActionResult.Failure("That didn't work.", it) }
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

    private fun ActionResult.describe(): String = when (this) {
        is ActionResult.Success -> spoken ?: "ok"
        is ActionResult.Failure -> spoken
        is ActionResult.NeedsPermission -> spoken
        is ActionResult.Unhandled -> "no executor could handle that"
    }

    private companion object {
        /**
         * Enough for a realistic send-a-message flow — open, tap contact, tap field, type, tap
         * send — with headroom, and short enough that a confused planner stops quickly.
         */
        const val DEFAULT_MAX_STEPS = 8

        /**
         * Repeats tolerated before giving up.
         *
         * One is worth forgiving — a screen can take a moment to settle, and the planner may
         * simply have looked too early. A second means it is not going to get there.
         */
        const val MAX_STALLS = 2
    }
}
