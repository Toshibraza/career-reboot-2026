package com.nova.core.agent

import com.nova.core.agent.screen.ScreenSnapshot

/**
 * What Nova decided to do about one utterance.
 *
 * A plan is a list rather than a single action because real commands chain
 * ("open whatsapp and message Amit"). Phase 1 rules mostly emit one action; the planner
 * that replaces them in Phase 3 will emit many, and nothing downstream has to change.
 */
data class Plan(
    val utterance: String,
    val actions: List<NovaAction>,
    /** 0f..1f. Rule matches are 1f; an LLM engine should report real confidence. */
    val confidence: Float = 1f,
    /** Spoken immediately, before execution, so long tasks feel responsive. */
    val acknowledgement: String? = null,
) {
    companion object {
        fun unsupported(utterance: String, reason: String): Plan = Plan(
            utterance = utterance,
            actions = listOf(NovaAction.Unsupported(utterance, reason)),
            confidence = 0f,
        )
    }
}

/**
 * The world state an [IntentEngine] is allowed to see when planning.
 *
 * Deliberately small. Anything added here becomes part of the contract every future engine
 * must cope with, so it should stay to facts that genuinely change the parse.
 */
class AgentContext(
    /** Human-readable labels of installed apps, used to resolve "open <app>". */
    val installedAppLabels: List<String> = emptyList(),
    /** Label of the app currently in the foreground, if known. */
    val foregroundApp: String? = null,
    val locale: String = "en",
    /**
     * Reads the current screen on demand.
     *
     * A provider rather than a value, because reading the screen means inspecting whatever
     * app the user happens to have open — a privacy-relevant act, not free context. The rule
     * engine never calls this, so ordinary commands like "open YouTube" never look at the
     * screen at all. Only an engine that genuinely needs to see pays that cost.
     */
    private val screenProvider: suspend () -> ScreenSnapshot? = { null },
) {
    suspend fun screen(): ScreenSnapshot? = screenProvider()
}

/** Outcome of executing one [NovaAction]. */
sealed interface ActionResult {

    data class Success(val spoken: String? = null) : ActionResult

    data class Failure(val spoken: String, val cause: Throwable? = null) : ActionResult

    /**
     * The action is supported but blocked until the user grants something.
     * The UI turns this into a tappable prompt rather than a dead end.
     */
    data class NeedsPermission(
        val permission: RequiredPermission,
        val spoken: String,
    ) : ActionResult

    /** No registered executor claimed the action. */
    data class Unhandled(val action: NovaAction) : ActionResult
}

/**
 * Permissions Nova has to ask for out-of-band — each of these is a settings screen the user
 * must visit, not a runtime dialog.
 */
enum class RequiredPermission {
    RECORD_AUDIO,
    CAMERA,
    READ_CONTACTS,
    CALL_PHONE,
    SEND_SMS,
    WRITE_SYSTEM_SETTINGS,
    ACCESSIBILITY_SERVICE,
    NOTIFICATION_LISTENER,
    USAGE_STATS,
    DEVICE_ADMIN,
    DO_NOT_DISTURB,
}

/** Everything that happened for one utterance, ready for the UI and the transcript log. */
data class AgentResponse(
    val plan: Plan,
    val results: List<ActionResult>,
    /** The single line Nova should say out loud. */
    val spoken: String,
) {
    val succeeded: Boolean get() = results.all { it is ActionResult.Success }
}
