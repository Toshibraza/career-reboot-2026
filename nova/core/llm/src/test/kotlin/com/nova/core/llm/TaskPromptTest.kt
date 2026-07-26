package com.nova.core.llm

import com.nova.core.agent.NovaAction
import com.nova.core.agent.ScrollDirection
import com.nova.core.agent.screen.ElementRole
import com.nova.core.agent.screen.ScreenElement
import com.nova.core.agent.screen.ScreenSnapshot
import com.nova.core.agent.task.PlannerDecision
import com.nova.core.agent.task.StepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPromptTest {

    private val screen = ScreenSnapshot(
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        elements = listOf(
            ScreenElement("Search", ElementRole.TEXT_FIELD, clickable = true, editable = true),
            ScreenElement("Amit", ElementRole.BUTTON, clickable = true),
        ),
    )

    // --- Mapping replies to decisions ----------------------------------------------------

    @Test
    fun `act replies map to actions`() {
        assertEquals(
            PlannerDecision.Act(NovaAction.OpenApp("WhatsApp"), null),
            TaskPrompt.parse("""{"decision":"act","action":"open_app","argument":"WhatsApp","message":"","rationale":""}"""),
        )
        assertEquals(
            PlannerDecision.Act(NovaAction.TapLabel("Amit"), "open the chat"),
            TaskPrompt.parse("""{"decision":"act","action":"tap","argument":"Amit","message":"","rationale":"open the chat"}"""),
        )
        assertEquals(
            PlannerDecision.Act(NovaAction.ScrollScreen(ScrollDirection.DOWN), null),
            TaskPrompt.parse("""{"decision":"act","action":"scroll_down","argument":"","message":"","rationale":""}"""),
        )
    }

    @Test
    fun `finish and blocked carry their message`() {
        assertEquals(
            PlannerDecision.Finished("Sent it."),
            TaskPrompt.parse("""{"decision":"finish","action":"none","argument":"","message":"Sent it.","rationale":""}"""),
        )
        assertEquals(
            PlannerDecision.Blocked("There's no contact called Amit."),
            TaskPrompt.parse("""{"decision":"blocked","action":"none","argument":"","message":"There's no contact called Amit.","rationale":""}"""),
        )
    }

    // --- Refusing to guess ---------------------------------------------------------------

    @Test
    fun `malformed json blocks rather than guessing an action`() {
        // A broken reply is not a reason to start touching someone's phone at random.
        val decision = TaskPrompt.parse("not json at all")
        assertTrue(decision is PlannerDecision.Blocked)
    }

    @Test
    fun `an action missing its argument blocks`() {
        listOf("tap", "type", "open_app").forEach { action ->
            val decision = TaskPrompt.parse(
                """{"decision":"act","action":"$action","argument":"","message":"","rationale":""}""",
            )
            assertTrue("$action with no argument should block", decision is PlannerDecision.Blocked)
        }
    }

    @Test
    fun `an unknown action blocks`() {
        val decision = TaskPrompt.parse(
            """{"decision":"act","action":"factory_reset","argument":"now","message":"","rationale":""}""",
        )
        assertTrue(decision is PlannerDecision.Blocked)
    }

    @Test
    fun `an echoed enum placeholder blocks rather than acting`() {
        // Verbatim from Qwen2.5-0.5B on device. The first prompt listed allowed values inside
        // the JSON template as "open_app|tap|type|...", and the model copied the placeholder
        // instead of choosing. It must never be read as a real action.
        val decision = TaskPrompt.parse(
            """{"decision":"act","action":"open_app|tap|type|scroll_up|home|back|none","argument":"","message":"Navigating...","rationale":""}""",
        )
        assertTrue(decision is PlannerDecision.Blocked)
    }

    @Test
    fun `the system prompt never shows pipe-separated values inside json`() {
        // Regression guard for the above: a small model copies whatever shape it is shown.
        val system = TaskPrompt.systemPrompt()
        assertTrue("\"action\":\"open_app|" !in system)
        assertTrue("Never write several separated by" in system)
    }

    @Test
    fun `prose around the json is tolerated`() {
        // Small models wrap replies in explanation or a code fence; the answer inside is still
        // correct and rejecting it would make the model look broken.
        val decision = TaskPrompt.parse(
            """
            Sure! Here is the next step:
            ```json
            {"decision":"act","action":"open_app","argument":"Settings","message":"","rationale":"need the app"}
            ```
            """.trimIndent(),
        )
        assertEquals(
            PlannerDecision.Act(NovaAction.OpenApp("Settings"), "need the app"),
            decision,
        )
    }

    @Test
    fun `an unknown decision blocks`() {
        val decision = TaskPrompt.parse(
            """{"decision":"improvise","action":"tap","argument":"OK","message":"","rationale":""}""",
        )
        assertTrue(decision is PlannerDecision.Blocked)
    }

    @Test
    fun `finish without a message still says something`() {
        assertEquals(
            PlannerDecision.Finished("Done."),
            TaskPrompt.parse("""{"decision":"finish","action":"none","argument":"","message":"","rationale":""}"""),
        )
    }

    // --- Prompt content ------------------------------------------------------------------

    @Test
    fun `user prompt carries the goal and the screen`() {
        val prompt = TaskPrompt.userPrompt("message Amit", screen, emptyList())
        assertTrue("Goal: message Amit" in prompt)
        assertTrue("App: WhatsApp" in prompt)
        assertTrue("- field: Search" in prompt)
        assertTrue("- button: Amit" in prompt)
        assertTrue("none" in prompt)
    }

    @Test
    fun `history tells the model what failed`() {
        val history = listOf(
            StepRecord(NovaAction.OpenApp("whatsapp"), "Opening WhatsApp.", succeeded = true),
            StepRecord(NovaAction.TapLabel("Amit"), "I couldn't find Amit on screen.", succeeded = false),
        )
        val prompt = TaskPrompt.userPrompt("message Amit", screen, history)

        assertTrue("""1. open_app "whatsapp" -> ok: Opening WhatsApp.""" in prompt)
        assertTrue("""2. tap "Amit" -> FAILED: I couldn't find Amit on screen.""" in prompt)
    }

    @Test
    fun `unreadable screen is stated rather than omitted`() {
        val prompt = TaskPrompt.userPrompt("do something", null, emptyList())
        assertTrue("(cannot read the screen)" in prompt)
    }

    @Test
    fun `system prompt forbids inventing labels and destructive actions`() {
        val system = TaskPrompt.systemPrompt()
        assertTrue("Never invent one." in system)
        assertTrue("destructive" in system)
    }
}
