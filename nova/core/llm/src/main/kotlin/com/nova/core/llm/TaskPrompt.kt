package com.nova.core.llm

import com.nova.core.agent.NovaAction
import com.nova.core.agent.ScrollDirection
import com.nova.core.agent.screen.ScreenSnapshot
import com.nova.core.agent.task.PlannerDecision
import com.nova.core.agent.task.StepRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The model's reply, kept flat rather than as a union.
 *
 * OpenAI's strict structured outputs cannot express a discriminated union cleanly, and a flat
 * shape with an explicit [decision] field is far harder for a model to get subtly wrong than
 * nested optional objects.
 */
@Serializable
internal data class PlannerReply(
    val decision: String = "",
    val action: String = "",
    val argument: String = "",
    val message: String = "",
    val rationale: String = "",
)

/**
 * Builds the prompts and maps the reply back to a [PlannerDecision].
 *
 * Separated from the HTTP client because this is the part most likely to be wrong, and it is
 * pure — no network, no device, fully testable.
 */
object TaskPrompt {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** JSON Schema handed to the model, so the reply shape is enforced rather than requested. */
    const val RESPONSE_SCHEMA: String = """
{
  "type": "object",
  "additionalProperties": false,
  "required": ["decision", "action", "argument", "message", "rationale"],
  "properties": {
    "decision": { "type": "string", "enum": ["act", "finish", "blocked"] },
    "action": {
      "type": "string",
      "enum": ["open_app", "tap", "type", "scroll_down", "scroll_up", "back", "home", "none"]
    },
    "argument": { "type": "string" },
    "message": { "type": "string" },
    "rationale": { "type": "string" }
  }
}
"""

    fun systemPrompt(): String = """
        You operate an Android phone for a user, one step at a time.

        You are given the user's goal, what is currently on screen, and what your previous
        steps achieved. Choose exactly ONE next action, then you will see the new screen.

        Actions:
        - open_app   argument = app name, e.g. "WhatsApp"
        - tap        argument = the visible label of the control, exactly as listed on screen
        - type       argument = the text to type into the focused field
        - scroll_down / scroll_up   argument is ignored
        - back / home              argument is ignored

        Rules:
        - Only tap labels that appear in the screen listing. Never invent one.
        - Tap a text field before typing into it.
        - When the goal is achieved, reply with decision "finish" and a short spoken message.
        - If you cannot achieve it — the contact is not there, the app is missing, the screen
          is unreadable — reply with decision "blocked" and say plainly why. Do not guess, and
          do not keep tapping in the hope something works.
        - Never take a destructive or irreversible action (deleting, paying, sending money,
          changing account settings) unless the user's goal explicitly asked for it.
        - Keep "message" short enough to speak aloud.
    """.trimIndent()

    fun userPrompt(
        goal: String,
        screen: ScreenSnapshot?,
        history: List<StepRecord>,
    ): String = buildString {
        appendLine("Goal: $goal")
        appendLine()

        appendLine("Screen:")
        appendLine(screen?.toPrompt() ?: "(cannot read the screen)")
        appendLine()

        if (history.isEmpty()) {
            appendLine("Steps so far: none. This is your first action.")
        } else {
            appendLine("Steps so far:")
            history.forEachIndexed { index, step ->
                val status = if (step.succeeded) "ok" else "FAILED"
                appendLine("${index + 1}. ${step.action.describe()} -> $status: ${step.outcome}")
            }
        }
    }.trim()

    /**
     * Maps the model's reply to a decision.
     *
     * Anything unparseable or nonsensical becomes [PlannerDecision.Blocked] rather than a
     * guessed action. A malformed reply is not a reason to touch someone's phone at random.
     */
    fun parse(raw: String): PlannerDecision {
        val reply = runCatching { json.decodeFromString<PlannerReply>(raw) }
            .getOrElse { return PlannerDecision.Blocked("I couldn't work out what to do next.") }

        return when (reply.decision.lowercase()) {
            "finish" -> PlannerDecision.Finished(reply.message.ifBlank { "Done." })
            "blocked" -> PlannerDecision.Blocked(reply.message.ifBlank { "I can't do that." })
            "act" -> reply.toAction()
                ?.let { PlannerDecision.Act(it, reply.rationale.ifBlank { null }) }
                ?: PlannerDecision.Blocked(reply.message.ifBlank { "I'm not sure what to do next." })

            else -> PlannerDecision.Blocked("I couldn't work out what to do next.")
        }
    }

    private fun PlannerReply.toAction(): NovaAction? = when (action.lowercase()) {
        // An action that needs an argument and did not get one is a malformed reply, not an
        // instruction to tap something blank.
        "open_app" -> argument.takeIf { it.isNotBlank() }?.let { NovaAction.OpenApp(it) }
        "tap" -> argument.takeIf { it.isNotBlank() }?.let { NovaAction.TapLabel(it) }
        "type" -> argument.takeIf { it.isNotBlank() }?.let { NovaAction.TypeText(it) }
        "scroll_down" -> NovaAction.ScrollScreen(ScrollDirection.DOWN)
        "scroll_up" -> NovaAction.ScrollScreen(ScrollDirection.UP)
        "back" -> NovaAction.GoBack
        "home" -> NovaAction.GoHome
        else -> null
    }

    /** Short description of a past action, for the history block. */
    private fun NovaAction.describe(): String = when (this) {
        is NovaAction.OpenApp -> "open_app \"$query\""
        is NovaAction.TapLabel -> "tap \"$label\""
        is NovaAction.TypeText -> "type \"$text\""
        is NovaAction.ScrollScreen -> "scroll ${direction.name.lowercase()}"
        NovaAction.GoBack -> "back"
        NovaAction.GoHome -> "home"
        else -> this::class.simpleName ?: "action"
    }
}
