package com.nova.core.agent.help

import com.nova.core.agent.ActionResult
import com.nova.core.agent.AgentContext
import com.nova.core.agent.NovaAction
import com.nova.core.agent.rules.RuleIntentEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {

    private val engine = RuleIntentEngine()

    private fun parse(utterance: String): NovaAction = runBlocking {
        engine.plan(utterance, AgentContext()).actions.single()
    }

    @Test
    fun `asking what Raza can do is answered by the list, not the model`() {
        // Routed ahead of conversation on purpose. Sending this to a model would be slow and
        // would describe whatever it imagines rather than what is actually built.
        // "What can you do" is here to pin a trap: normalisation strips "can you" as
        // politeness, so the rule never sees the sentence the user said.
        for (phrasing in listOf(
            "help",
            "what can you do",
            "what do you do",
            "what can I say",
            "what can I ask you",
            "commands",
            "tum kya kar sakte ho",
        )) {
            assertEquals(phrasing, NovaAction.ListCapabilities, parse(phrasing))
        }
    }

    @Test
    fun `a question that merely starts the same way is not the capability list`() {
        // The cost of the trap above: "what do" had to be matched, and unanchored it would
        // have swallowed these. They stay questions — claimed here by the recall rule, with
        // conversation picking up the miss at runtime.
        for (question in listOf(
            "what do you think about this",
            "what do you know about Kotlin",
        )) {
            assertTrue(question, parse(question) !is NovaAction.ListCapabilities)
        }
    }

    @Test
    fun `the spoken answer is short enough to listen to`() {
        val spoken = Capabilities.spoken()

        // Forty examples read aloud is worse than none — by the fourth the listener has lost
        // the first. The long list belongs on screen.
        assertTrue(spoken, spoken.length < 320)
        assertTrue(spoken, "on screen" in spoken)
    }

    @Test
    fun `the spoken answer contains a sentence a user can repeat verbatim`() {
        val spoken = Capabilities.spoken()

        // A capability list is only useful if it hands over words that work. This one is
        // parsed here to prove it does.
        assertTrue(parse("open YouTube") is NovaAction.OpenApp)
        assertTrue(spoken, "open YouTube" in spoken)
    }

    @Test
    fun `every listed example actually parses`() {
        // The list is hand-written and will drift unless something checks it. This is that
        // something: an example Raza cannot understand is worse than no example, because the
        // user blames themselves.
        val unparsed = Capabilities.listed()
            .flatMap { it.examples }
            .filter { parse(it) is NovaAction.Unsupported }

        assertEquals(emptyList<String>(), unparsed)
    }

    @Test
    fun `the executor answers with the list`() = runBlocking {
        val result = CapabilitiesActionExecutor().execute(NovaAction.ListCapabilities)

        assertEquals(Capabilities.spoken(), (result as ActionResult.Success).spoken)
    }
}
