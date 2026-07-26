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
        }
        return "${spokenCommand()} $schedule"
    }

    /** "say buy milk" reads better as "remind you to buy milk". */
    private fun Routine.spokenCommand(): String =
        command.removePrefix("say ").let { rest ->
            if (rest == command) rest else "remind you to $rest"
        }

    private companion object {
        const val SPOKEN_LIMIT = 5
    }
}
