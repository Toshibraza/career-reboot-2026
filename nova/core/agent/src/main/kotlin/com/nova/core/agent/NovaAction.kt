package com.nova.core.agent

/**
 * A single, device-agnostic thing Nova can do.
 *
 * Actions are pure data on purpose: [IntentEngine] produces them without knowing how they are
 * carried out, and an [ActionExecutor] carries them out without knowing how they were parsed.
 * That split is what lets the rule engine be swapped for an LLM, and the Android executor be
 * swapped for a Windows or IoT one, without either side changing.
 */
sealed interface NovaAction {

    /** Launch an app matched loosely by name, e.g. "youtube", "whats app". */
    data class OpenApp(val query: String) : NovaAction

    /** Send the named app to the background / clear it from the foreground. */
    data class CloseApp(val query: String) : NovaAction

    /** Return to the home screen. */
    data object GoHome : NovaAction

    /** Navigate back once. */
    data object GoBack : NovaAction

    /** Show the recent-apps switcher. */
    data object OpenRecents : NovaAction

    /** Pull down the notification shade. */
    data object OpenNotifications : NovaAction

    /**
     * Tap the on-screen control whose label best matches [label].
     *
     * The match is deliberately fuzzy — the input is a speech transcript — but it declines
     * rather than guessing when nothing is close. Tapping the wrong button in someone's
     * banking app is far worse than saying "I couldn't find that".
     */
    data class TapLabel(val label: String) : NovaAction

    data class ScrollScreen(val direction: ScrollDirection) : NovaAction

    /** Type [text] into whatever field currently has focus. */
    data class TypeText(val text: String) : NovaAction

    /** Describe what is currently on screen out loud. */
    data object ReadScreen : NovaAction

    /** Store a fact so it can be asked for later. */
    data class Remember(val subject: String, val detail: String) : NovaAction

    /** Answer a question from what has been stored. */
    data class Recall(val subject: String) : NovaAction

    /** List everything Nova is holding. */
    data object RecallAll : NovaAction

    data class ForgetMemory(val subject: String) : NovaAction

    /**
     * Schedule [command] to run at [trigger].
     *
     * The command is an utterance, not a parsed plan — see [com.nova.core.agent.routine.Routine].
     */
    data class CreateRoutine(
        val trigger: com.nova.core.agent.routine.RoutineTrigger,
        val command: String,
        /** What to say back when it is created, e.g. "every day at 8 am". */
        val spokenSchedule: String,
    ) : NovaAction

    data object ListRoutines : NovaAction

    data class DeleteRoutine(val query: String) : NovaAction

    /** Read out what is currently in the notification shade. */
    data object ReadNotifications : NovaAction

    /**
     * Place a call to a contact matched by name.
     *
     * Never dials directly. Two lossy steps stand between the user and the number — speech
     * recognition, then fuzzy name matching — so this only ever proposes, and the call is
     * placed by [ConfirmPending] after the user hears who and which number.
     */
    data class CallContact(val query: String) : NovaAction

    /** Propose an SMS. Same rule: proposes only, never sends. */
    data class SendSms(val query: String, val message: String) : NovaAction

    /** Report on Raza's own health — permissions, services, models, keys. */
    data object RunDiagnostics : NovaAction

    /** Look something up on the web and read back what was found. */
    data class SearchWeb(val query: String) : NovaAction

    /** Carry out whatever was last proposed. */
    data object ConfirmPending : NovaAction

    /** Throw away whatever was last proposed. */
    data object CancelPending : NovaAction

    /**
     * Read the screen's text as pixels rather than as a node tree.
     *
     * Distinct from [ReadScreen], which lists controls an app exposes. This is for what
     * accessibility cannot see: a photo, a document, a game, a canvas-drawn view.
     */
    data object ReadScreenText : NovaAction

    data class SetFlashlight(val on: Boolean) : NovaAction

    data class SetVolume(val stream: VolumeStream, val level: LevelChange) : NovaAction

    data class SetBrightness(val level: LevelChange) : NovaAction

    data object LockScreen : NovaAction

    /**
     * Play something — a song, a video, an artist.
     *
     * Distinct from [OpenApp]: "play Coke Studio" is not a request to launch YouTube and leave
     * the user to type. Distinct from [SearchWeb] too, because the answer is meant to be
     * watched rather than read aloud.
     */
    data class PlayMedia(val query: String) : NovaAction

    /** Say what Raza can actually do. */
    data object ListCapabilities : NovaAction

    data object TakeScreenshot : NovaAction

    /** Say something back to the user. Terminal action for chit-chat and confirmations. */
    data class Speak(val text: String) : NovaAction

    /**
     * Answer in conversation rather than by operating the phone.
     *
     * Distinct from [Speak], which says a line already decided elsewhere. This one has to work
     * out what the answer is — from what Raza has been told, from the web, or from the model —
     * and it remembers the exchange so a follow-up like "what about Germany?" still makes
     * sense.
     */
    data class Converse(val utterance: String) : NovaAction

    /**
     * Nova understood that something was asked but has no capability for it yet.
     * Kept as a first-class action so the UI can log it and the roadmap can be driven by
     * what users actually ask for.
     */
    data class Unsupported(val utterance: String, val reason: String) : NovaAction
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

enum class VolumeStream {
    MEDIA,
    RING,
    ALARM,
    CALL,
}

/** How a 0..100 scalar (volume, brightness) should change. */
sealed interface LevelChange {

    /** Set to an exact percentage, clamped to 0..100 by the executor. */
    data class Absolute(val percent: Int) : LevelChange

    /** Nudge by a signed percentage, e.g. +10 for "volume up". */
    data class Relative(val deltaPercent: Int) : LevelChange

    data object Min : LevelChange

    data object Max : LevelChange
}
