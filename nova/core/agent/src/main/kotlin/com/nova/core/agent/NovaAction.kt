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

    data class SetFlashlight(val on: Boolean) : NovaAction

    data class SetVolume(val stream: VolumeStream, val level: LevelChange) : NovaAction

    data class SetBrightness(val level: LevelChange) : NovaAction

    data object LockScreen : NovaAction

    data object TakeScreenshot : NovaAction

    /** Say something back to the user. Terminal action for chit-chat and confirmations. */
    data class Speak(val text: String) : NovaAction

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
