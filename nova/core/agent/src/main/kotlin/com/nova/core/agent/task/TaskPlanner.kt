package com.nova.core.agent.task

import com.nova.core.agent.NovaAction
import com.nova.core.agent.screen.ScreenSnapshot

/** One completed step, fed back to the planner so it can see what its last move achieved. */
data class StepRecord(
    val action: NovaAction,
    /** What the executor said — success text, or the reason it failed. */
    val outcome: String,
    val succeeded: Boolean,
)

/** What the planner wants to do next. */
sealed interface PlannerDecision {

    /**
     * Take one action, then look again.
     *
     * One at a time, not a batch: after a tap the screen is different, and any further steps
     * planned against the old screen would be guesses. [rationale] is carried for logs and
     * debugging, not spoken.
     */
    data class Act(val action: NovaAction, val rationale: String? = null) : PlannerDecision

    /** The goal is achieved. [spoken] is what Nova says. */
    data class Finished(val spoken: String) : PlannerDecision

    /** The goal cannot be achieved from here, and the planner says why. */
    data class Blocked(val spoken: String) : PlannerDecision
}

/**
 * Chooses the next step toward a goal, given what is on screen and what has happened so far.
 *
 * Deliberately not an [com.nova.core.agent.IntentEngine]: an engine turns one utterance into a
 * plan up front, which is the right shape for "turn on the flashlight" and the wrong shape for
 * driving a UI. Sending a message means tapping a contact, waiting for a screen that did not
 * exist when planning started, typing, then finding a send button whose label you could not
 * have known. That requires looking between every step.
 */
interface TaskPlanner {

    suspend fun next(
        goal: String,
        screen: ScreenSnapshot?,
        history: List<StepRecord>,
    ): PlannerDecision
}
