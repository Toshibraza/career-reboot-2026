package com.nova.core.agent.rules

import com.nova.core.agent.AgentContext
import com.nova.core.agent.IntentEngine
import com.nova.core.agent.LevelChange
import com.nova.core.agent.NovaAction
import com.nova.core.agent.Plan
import com.nova.core.agent.ScrollDirection
import com.nova.core.agent.VolumeStream

/**
 * Deterministic, offline intent parsing for the commands people actually use every day.
 *
 * Rules are tried in order, so specific patterns are registered before general ones — without
 * that, "close the flashlight" gets eaten by the generic "close <app>" rule. Anything unmatched
 * becomes [NovaAction.Unsupported] with zero confidence, which is what lets
 * [com.nova.core.agent.FallbackIntentEngine] hand the long tail to an LLM later.
 */
class RuleIntentEngine : IntentEngine {

    override val name: String = "rules"

    override suspend fun plan(utterance: String, context: AgentContext): Plan {
        val text = Utterance.normalise(utterance)
        if (text.isEmpty()) return Plan.unsupported(utterance, "Nothing to parse.")

        for (rule in RULES) {
            val actions = rule.apply(text, utterance, context)
            if (!actions.isNullOrEmpty()) {
                return Plan(utterance = utterance, actions = actions, confidence = 1f)
            }
        }
        return Plan.unsupported(utterance, "No rule matched.")
    }

    private companion object {

        /** Words meaning "raise" and "lower", shared by the volume and brightness rules. */
        const val UP = "\\b(?:up|increase|raise|louder|higher|brighter)\\b"
        const val DOWN = "\\b(?:down|decrease|lower|reduce|quieter|softer|dimmer|dim)\\b"
        const val OFF = "\\b(?:off|disable|stop|kill|close|shut)\\b"

        /**
         * Guard for rules that would otherwise steal an app launch. "open lock screen app" is
         * a request for an app named "lock screen", not a request to lock the phone.
         */
        const val NOT_A_LAUNCH = "^(?!(?:open|launch|start|run|switch to)\\b)"

        val RULES: List<CommandRule> = listOf(

            // --- Torch -------------------------------------------------------------------
            // Registered first: "close the torch" must not reach the close-app rule.
            rule("flashlight", "\\b(?:flash ?light|torch)\\b") {
                listOf(NovaAction.SetFlashlight(on = !it.contains(OFF)))
            },

            // --- Volume ------------------------------------------------------------------
            rule("volume-set", "\\bvolume\\b.*\\b(?:to|at)\\b|\\bset\\b.*\\bvolume\\b") {
                val percent = Numbers.firstIn(it.text) ?: return@rule null
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Absolute(percent)))
            },

            rule("volume-max", "\\b(?:max|maximum|full)\\b") {
                if (!it.contains("\\bvolume\\b")) return@rule null
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Max))
            },

            rule("volume-mute", "\\b(?:mute|silence|silent)\\b") {
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Min))
            },

            rule("volume-step", "\\bvolume\\b|\\b(?:louder|quieter|softer)\\b") {
                val delta = when {
                    it.contains(UP) -> 10
                    it.contains(DOWN) -> -10
                    else -> return@rule null
                }
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Relative(delta)))
            },

            // --- Brightness --------------------------------------------------------------
            rule("brightness-set", "\\bbrightness\\b.*\\b(?:to|at)\\b|\\bset\\b.*\\bbrightness\\b") {
                val percent = Numbers.firstIn(it.text) ?: return@rule null
                listOf(NovaAction.SetBrightness(LevelChange.Absolute(percent)))
            },

            rule("brightness-step", "\\bbright(?:ness)?\\b|\\bdim\\b") {
                val delta = when {
                    it.contains(UP) -> 20
                    it.contains(DOWN) -> -20
                    else -> return@rule null
                }
                listOf(NovaAction.SetBrightness(LevelChange.Relative(delta)))
            },

            // --- Navigation --------------------------------------------------------------
            // Anchored rather than loose: a bare \bhome\b would send "open google home" to
            // the launcher instead of opening the app.
            simpleRule(
                "lock",
                "^lock$|$NOT_A_LAUNCH.*\\block\\b.*\\b(?:phone|screen|device|it)\\b",
                NovaAction.LockScreen,
            ),
            simpleRule("screenshot", "\\bscreen ?shot\\b|\\bcapture (?:the )?screen\\b", NovaAction.TakeScreenshot),
            simpleRule("home", "^(?:go |take me )?(?:to )?home(?: screen)?$|\\bgo home\\b", NovaAction.GoHome),
            simpleRule("back", "\\bgo back\\b|^back$", NovaAction.GoBack),
            simpleRule(
                "recents",
                "\\brecent apps?\\b|\\bapp switcher\\b|^recents$",
                NovaAction.OpenRecents,
            ),
            simpleRule(
                "notifications",
                "\\bnotification (?:shade|panel)\\b|\\b(?:show|open|pull down) (?:my )?notifications\\b",
                NovaAction.OpenNotifications,
            ),

            simpleRule(
                "read-screen",
                "\\bwhat(?:s| is)? on (?:the |this )?screen\\b|\\bread (?:the |this )?screen\\b|\\bwhat (?:do you |can you )?see\\b|\\bwhat does (?:it|this) say\\b",
                NovaAction.ReadScreen,
            ),

            // --- On-screen control -------------------------------------------------------
            // Registered before the app rules: these are all anchored on their own verbs, so
            // they never collide with "open <app>", but keeping them adjacent to navigation
            // makes the precedence obvious to whoever adds the next rule.
            rule("type", "^(?:type|enter|write|input|dictate)\\s+(?:in\\s+)?(.+)$") {
                val dictated = it.rawAfter("type", "enter", "write", "input", "dictate")
                    ?: it.group(1)
                dictated.takeIf(String::isNotBlank)?.let { text -> listOf(NovaAction.TypeText(text)) }
            },

            rule("scroll", "\\b(?:scroll|swipe|page)\\b") {
                val direction = when {
                    it.contains("\\b(?:down|downwards?)\\b") -> ScrollDirection.DOWN
                    it.contains("\\b(?:up|upwards?)\\b") -> ScrollDirection.UP
                    it.contains("\\bleft\\b") -> ScrollDirection.LEFT
                    it.contains("\\bright\\b") -> ScrollDirection.RIGHT
                    else -> return@rule null
                }
                listOf(NovaAction.ScrollScreen(direction))
            },

            rule("tap", "^(?:tap|click|press|select|touch|hit)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+button)?$") {
                it.group(1).takeIf(String::isNotBlank)?.let { label ->
                    listOf(NovaAction.TapLabel(label))
                }
            },

            // --- Apps --------------------------------------------------------------------
            simpleRule(
                "close-current",
                "^(?:close|quit|exit|kill)(?: the)?(?: current)?(?: app)?$",
                NovaAction.CloseApp(""),
            ),

            rule("close-app", "^(?:close|quit|exit|kill)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$") {
                it.group(1).takeIf(String::isNotBlank)?.let { app -> listOf(NovaAction.CloseApp(app)) }
            },

            rule("open-app", "^(?:open|launch|start|run|go to|switch to)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$") {
                it.group(1).takeIf(String::isNotBlank)?.let { app -> listOf(NovaAction.OpenApp(app)) }
            },

            // --- Social ------------------------------------------------------------------
            simpleRule(
                "greeting",
                "^(?:hi|hello|hey|good morning|good evening|good afternoon)$",
                NovaAction.Speak("Hello. What can I do?"),
            ),
            simpleRule("thanks", "^(?:thanks|thank you|cheers)$", NovaAction.Speak("Any time.")),
            simpleRule(
                "identity",
                "\\bwho are you\\b|\\bwhat(?:s| is) your name\\b",
                NovaAction.Speak("I'm Nova, your assistant on this phone."),
            ),
        )

        /** Picks the audio stream named in the utterance, defaulting to media. */
        fun streamIn(text: String): VolumeStream = when {
            Regex("\\bring(?:er|tone)?\\b|\\bnotification\\b").containsMatchIn(text) -> VolumeStream.RING
            Regex("\\balarm\\b").containsMatchIn(text) -> VolumeStream.ALARM
            Regex("\\b(?:in ?call|call|voice)\\b").containsMatchIn(text) -> VolumeStream.CALL
            else -> VolumeStream.MEDIA
        }
    }
}
