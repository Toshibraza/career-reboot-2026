package com.nova.core.llm

import com.nova.core.agent.NovaAction
import com.nova.core.agent.task.PlannerDecision
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class OpenAiTaskPlannerTest {

    private fun plannerThatFailsWith(failure: Throwable) = OpenAiTaskPlanner(
        object : ChatClient {
            override suspend fun complete(system: String, user: String): String = throw failure
        },
    )

    private suspend fun spokenFor(failure: Throwable): String =
        (plannerThatFailsWith(failure).next("do something", null, emptyList()) as PlannerDecision.Blocked)
            .spoken

    @Test
    fun `an exhausted quota is not reported as rate limiting`() = runTest {
        // Verified against the live API: a 429 with insufficient_quota is a billing problem
        // that never clears by waiting. "Try again in a moment" would loop the user forever.
        val quota = OpenAiHttpException(429, """{"error":{"code":"insufficient_quota"}}""")
        assertEquals("My OpenAI account is out of credit.", spokenFor(quota))
    }

    @Test
    fun `real rate limiting still says to wait`() = runTest {
        val throttled = OpenAiHttpException(429, """{"error":{"code":"rate_limit_exceeded"}}""")
        assertEquals("I'm being rate limited. Try again in a moment.", spokenFor(throttled))
    }

    @Test
    fun `a rejected key is distinguished from a missing signal`() = runTest {
        assertEquals("My API key was rejected.", spokenFor(OpenAiHttpException(401, "")))
        assertEquals(
            "I couldn't reach the network to work that out.",
            spokenFor(IOException("no route to host")),
        )
    }

    @Test
    fun `server trouble is reported as such`() = runTest {
        assertEquals("The service is having trouble right now.", spokenFor(OpenAiHttpException(503, "")))
    }

    @Test
    fun `a good reply becomes an action`() = runTest {
        val planner = OpenAiTaskPlanner(
            object : ChatClient {
                override suspend fun complete(system: String, user: String): String =
                    """{"decision":"act","action":"open_app","argument":"Settings","message":"","rationale":""}"""
            },
        )
        assertEquals(
            PlannerDecision.Act(NovaAction.OpenApp("Settings"), null),
            planner.next("open settings", null, emptyList()),
        )
    }
}
