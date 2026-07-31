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
    fun `the system prompt shows pipes only as a labelled mistake`() {
        // A small model copies whatever shape it is shown, so the pipe form may appear only
        // where it is explicitly marked wrong. Telling it "choose one word" was not enough on
        // its own; the WRONG/RIGHT pair is what actually fixed the behaviour on device.
        val system = TaskPrompt.systemPrompt()

        assertTrue("Never write several separated by" in system)
        assertTrue("WRONG" in system)
        assertTrue("RIGHT" in system)

        // Every pipe form present must sit after the WRONG marker and before the RIGHT one.
        val wrongAt = system.indexOf("WRONG")
        val rightAt = system.indexOf("RIGHT")
        val pipeAt = system.indexOf("\"action\":\"open_app|")

        assertTrue("the pipe example must be labelled WRONG", pipeAt in (wrongAt + 1) until rightAt)
        assertTrue(
            "no second unlabelled pipe example",
            system.indexOf("\"action\":\"open_app|", pipeAt + 1) < 0,
        )
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

    // --- Vocabulary drift ----------------------------------------------------------------

    @Test
    fun `schema and system prompt advertise exactly the actions the parser accepts`() {
        // The vocabulary lives in three places: the schema's enum, the prompt's instructions,
        // and the parser. A word in one but not another means the model is promised an action
        // that comes back "blocked", or offered fewer than it really has.
        val system = TaskPrompt.systemPrompt()

        for (action in TaskPrompt.PLANNER_ACTIONS) {
            assertTrue("schema is missing \"$action\"", "\"$action\"" in TaskPrompt.RESPONSE_SCHEMA)
            assertTrue("system prompt never mentions \"$action\"", action in system)
        }
    }

    @Test
    fun `every advertised action parses to a decision, not a malformed-reply block`() {
        // "none" is the finish/blocked placeholder; every other advertised action must map to
        // a real NovaAction when given a plausible argument.
        for (action in TaskPrompt.PLANNER_ACTIONS.filterNot { it == "none" }) {
            val decision = TaskPrompt.parse(
                """{"decision":"act","action":"$action","argument":"Something","message":"","rationale":""}""",
            )
            assertTrue(
                "\"$action\" is advertised but parses to $decision",
                decision is PlannerDecision.Act,
            )
        }
    }

    @Test
    fun `the parser accepts nothing outside the advertised vocabulary`() {
        // The other direction of drift: a parser case added without advertising it would work
        // in tests and never be chosen by a schema-constrained model.
        val decision = TaskPrompt.parse(
            """{"decision":"act","action":"long_press","argument":"Send","message":"","rationale":""}""",
        )
        assertTrue(decision is PlannerDecision.Blocked)
    }
}
