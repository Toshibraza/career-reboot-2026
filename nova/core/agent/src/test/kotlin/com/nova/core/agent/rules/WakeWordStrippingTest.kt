package com.nova.core.agent.rules

import com.nova.core.agent.AgentContext
import com.nova.core.agent.NovaAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wake word has to survive being misheard.
 *
 * "Raza" is not in any recogniser's vocabulary, so it arrives as whatever real word sounds
 * closest. A command that is recognised but whose wake word is not stripped is exactly as
 * broken as one never heard — it becomes a request to open an app called "razor open youtube".
 */
class WakeWordStrippingTest {

    private val engine = RuleIntentEngine()

    private fun parse(utterance: String): NovaAction = runBlocking {
        engine.plan(utterance, AgentContext()).actions.single()
    }

    @Test
    fun `the exact name is stripped`() {
        assertEquals(NovaAction.OpenApp("youtube"), parse("raza open youtube"))
        assertEquals(NovaAction.OpenApp("youtube"), parse("hey raza open youtube"))
        assertEquals(NovaAction.OpenApp("youtube"), parse("okay Raza, open youtube"))
    }

    @Test
    fun `real mishearings are stripped too`() {
        // These are what a recogniser actually returns for the name, not invented spellings.
        listOf(
            "razor open youtube",
            "rasa open youtube",
            "hey razer open youtube",
            "raja open youtube",
            "rasha open youtube",
        ).forEach {
            assertEquals("failed for '$it'", NovaAction.OpenApp("youtube"), parse(it))
        }
    }

    @Test
    fun `ordinary words are not mistaken for the wake word`() {
        // The cost of a loose threshold is a command losing its first word. "Raise the
        // brightness" must not become "the brightness".
        assertEquals(NovaAction.SetBrightness(com.nova.core.agent.LevelChange.Relative(20)), parse("raise the brightness"))
        assertEquals(NovaAction.OpenApp("camera"), parse("open camera"))
        assertEquals(NovaAction.SetFlashlight(on = true), parse("turn on the flashlight"))
    }

    @Test
    fun `a command with no wake word is untouched`() {
        assertEquals(NovaAction.OpenApp("telegram"), parse("open telegram"))
        // "open whats app" keeps only "whats" because the open-app rule strips a trailing
        // "app" — which is fine, since "whats" still resolves WhatsApp by prefix.
        assertEquals(NovaAction.OpenApp("whats"), parse("open whats app"))
    }
}
