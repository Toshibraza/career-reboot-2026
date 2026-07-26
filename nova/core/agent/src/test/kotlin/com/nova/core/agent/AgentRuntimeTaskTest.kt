package com.nova.core.agent

import com.nova.core.agent.rules.RuleIntentEngine
import com.nova.core.agent.screen.ScreenSnapshot
import com.nova.core.agent.task.PlannerDecision
import com.nova.core.agent.task.StepRecord
import com.nova.core.agent.task.TaskPlanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The observe-act loop, driven by a scripted planner.
 *
 * No network and no Android: the loop's job is to stop at the right moment and report
 * honestly, and both are decidable from a fake.
 */
class AgentRuntimeTaskTest {

    /** Replays a fixed list of decisions, recording what it was told about each step. */
    private class ScriptedPlanner(
        private val decisions: List<PlannerDecision>,
    ) : TaskPlanner {
        var calls = 0
            private set
        val seenHistory = mutableListOf<List<StepRecord>>()

        override suspend fun next(
            goal: String,
            screen: ScreenSnapshot?,
            history: List<StepRecord>,
        ): PlannerDecision {
            seenHistory += history.toList()
            return decisions.getOrElse(calls++) { PlannerDecision.Blocked("ran out of script") }
        }
    }

    private class RecordingExecutor(
        private val result: (NovaAction) -> ActionResult = { ActionResult.Success("ok") },
    ) : ActionExecutor {
        val executed = mutableListOf<NovaAction>()
        override val name = "recording"
        override fun canHandle(action: NovaAction) = action !is NovaAction.Unsupported
        override suspend fun execute(action: NovaAction): ActionResult {
            executed += action
            return result(action)
        }
    }

    private fun runtime(
        planner: TaskPlanner?,
        executor: ActionExecutor,
        maxSteps: Int = 8,
    ) = AgentRuntime(
        intentEngine = RuleIntentEngine(),
        executors = listOf(executor),
        taskPlanner = planner,
        maxSteps = maxSteps,
    )

    @Test
    fun `recognised commands never reach the planner`() = runTest {
        val planner = ScriptedPlanner(listOf(PlannerDecision.Finished("should not happen")))
        val executor = RecordingExecutor()

        val response = runtime(planner, executor).handle("turn on the flashlight")

        assertEquals(0, planner.calls)
        assertEquals(listOf(NovaAction.SetFlashlight(on = true)), executor.executed)
        assertEquals("ok", response.spoken)
    }

    @Test
    fun `unrecognised commands escalate to the planner`() = runTest {
        val planner = ScriptedPlanner(
            listOf(
                PlannerDecision.Act(NovaAction.OpenApp("whatsapp")),
                PlannerDecision.Act(NovaAction.TapLabel("Amit")),
                PlannerDecision.Finished("Sent it."),
            ),
        )
        val executor = RecordingExecutor()

        val response = runtime(planner, executor).handle("send Amit a message on whatsapp")

        assertEquals(
            listOf(NovaAction.OpenApp("whatsapp"), NovaAction.TapLabel("Amit")),
            executor.executed,
        )
        assertEquals("Sent it.", response.spoken)
    }

    @Test
    fun `each step sees the outcome of the last`() = runTest {
        val planner = ScriptedPlanner(
            listOf(
                PlannerDecision.Act(NovaAction.TapLabel("Send")),
                PlannerDecision.Finished("Done."),
            ),
        )
        val executor = RecordingExecutor { ActionResult.Failure("I couldn't find Send on screen.") }

        runtime(planner, executor).handle("do the thing")

        assertEquals(emptyList<StepRecord>(), planner.seenHistory[0])
        val afterFirstStep = planner.seenHistory[1].single()
        assertEquals("I couldn't find Send on screen.", afterFirstStep.outcome)
        assertTrue(!afterFirstStep.succeeded)
    }

    @Test
    fun `the step cap stops a planner that would loop forever`() = runTest {
        // A planner that misreads a screen can otherwise tap indefinitely, and every step is
        // a real touch on someone's phone.
        val planner = ScriptedPlanner(List(100) { PlannerDecision.Act(NovaAction.ScrollScreen(ScrollDirection.DOWN)) })
        val executor = RecordingExecutor()

        val response = runtime(planner, executor, maxSteps = 3).handle("keep going forever")

        assertEquals(3, executor.executed.size)
        assertEquals("I got part of the way but couldn't finish that.", response.spoken)
    }

    @Test
    fun `a missing permission stops the run immediately`() = runTest {
        val planner = ScriptedPlanner(List(5) { PlannerDecision.Act(NovaAction.TapLabel("Send")) })
        val executor = RecordingExecutor {
            ActionResult.NeedsPermission(
                RequiredPermission.ACCESSIBILITY_SERVICE,
                "Turn on Nova's accessibility service and I can do that.",
            )
        }

        val response = runtime(planner, executor).handle("send a message")

        // Retrying will not grant the permission, and the user should hear about it now.
        assertEquals(1, executor.executed.size)
        assertEquals("Turn on Nova's accessibility service and I can do that.", response.spoken)
    }

    @Test
    fun `a blocked planner reports its own reason`() = runTest {
        val planner = ScriptedPlanner(
            listOf(PlannerDecision.Blocked("I can't see a contact called Amit.")),
        )
        val executor = RecordingExecutor()

        val response = runtime(planner, executor).handle("message Amit")

        assertEquals(0, executor.executed.size)
        assertEquals("I can't see a contact called Amit.", response.spoken)
    }

    @Test
    fun `a planner that throws does not crash the assistant`() = runTest {
        val planner = object : TaskPlanner {
            override suspend fun next(
                goal: String,
                screen: ScreenSnapshot?,
                history: List<StepRecord>,
            ): PlannerDecision = throw IllegalStateException("network died")
        }

        val response = runtime(planner, RecordingExecutor()).handle("do something clever")

        assertEquals("I couldn't work that out.", response.spoken)
    }

    @Test
    fun `without a planner unrecognised commands are declined as before`() = runTest {
        val response = runtime(planner = null, executor = RecordingExecutor())
            .handle("explain quantum computing")

        assertEquals("I can't do that yet.", response.spoken)
    }
}
