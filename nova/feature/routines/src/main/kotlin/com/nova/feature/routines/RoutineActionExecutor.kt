package com.nova.feature.routines

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.routine.Routine
import com.nova.core.agent.routine.RoutineStore
import com.nova.core.agent.routine.RoutineTrigger
import java.util.UUID

/** Creates, lists and cancels scheduled commands. */
class RoutineActionExecutor(
    private val store: RoutineStore,
    private val scheduler: RoutineScheduler,
) : ActionExecutor {

    override val name: String = "routines"

    override fun canHandle(action: NovaAction): Boolean = when (action) {
        is NovaAction.CreateRoutine,
        is NovaAction.DeleteRoutine,
        NovaAction.ListRoutines,
        -> true

        else -> false
    }

    override suspend fun execute(action: NovaAction): ActionResult = when (action) {
        is NovaAction.CreateRoutine -> create(action)

        NovaAction.ListRoutines -> {
            val routines = store.all()
            if (routines.isEmpty()) {
                ActionResult.Success("Nothing scheduled.")
            } else {
                ActionResult.Success(
                    routines.take(SPOKEN_LIMIT).joinToString(". ") { it.describe() },
                )
            }
        }

        is NovaAction.DeleteRoutine -> store.removeMatching(action.query)
            ?.let { removed ->
                scheduler.cancel(removed.id)
                ActionResult.Success("Cancelled ${removed.spokenCommand()}.")
            }
            ?: ActionResult.Failure("I couldn't find that one.")

        else -> ActionResult.Unhandled(action)
    }

    private suspend fun create(action: NovaAction.CreateRoutine): ActionResult {
        val routine = Routine(
            id = UUID.randomUUID().toString(),
            trigger = action.trigger,
            command = action.command,
            createdAt = System.currentTimeMillis(),
        )

        store.add(routine)
        scheduler.schedule(routine)

        // Says the schedule back. A reminder set for the wrong time is worse than one that
        // failed outright, and hearing "at 8 pm" is how the user catches it.
        return ActionResult.Success("I'll ${routine.spokenCommand()} ${action.spokenSchedule}.")
    }

    private fun Routine.describe(): String {
        val schedule = when (val trigger = trigger) {
            is RoutineTrigger.Daily -> "every day at ${trigger.at.spoken()}"
            is RoutineTrigger.OnceAt -> "at ${trigger.at.spoken()}"
            is RoutineTrigger.BatteryBelow -> "when the battery drops below ${trigger.percent} percent"
            RoutineTrigger.PowerConnected -> "when you plug in"
            RoutineTrigger.PowerDisconnected -> "when you unplug the charger"
        }
        return "${spokenCommand()} $schedule"
    }

    /**
     * How to describe the command out loud.
     *
     * "Say buy milk at 6 pm" reads better as "remind you to buy milk" — but only when it came
     * from "remind me to", which is exactly the one-off trigger. A user who wrote "say battery
     * is getting low" gets "I'll remind you to battery is getting low" otherwise, which is not
     * English.
     */
    private fun Routine.spokenCommand(): String {
        val spoken = command.removePrefix("say ")
        val isDictated = spoken != command

        return when {
            isDictated && trigger is RoutineTrigger.OnceAt -> "remind you to $spoken"
            isDictated -> "say $spoken"
            else -> command
        }
    }

    private companion object {
        const val SPOKEN_LIMIT = 5
    }
}
