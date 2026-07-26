package com.nova.core.agent.rules

import com.nova.core.agent.AgentContext
import com.nova.core.agent.IntentEngine
import com.nova.core.agent.LevelChange
import com.nova.core.agent.NovaAction
import com.nova.core.agent.Plan
import com.nova.core.agent.ScrollDirection
import com.nova.core.agent.VolumeStream
import com.nova.core.agent.routine.RoutineTrigger
import com.nova.core.agent.routine.TimeOfDay

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

        /**
         * A second instruction bolted onto the first.
         *
         * "open whatsapp and message amit" would otherwise be read as a request to launch an
         * app named "whatsapp and message amit". Single-command rules decline these so the
         * task planner gets them — and when no planner is configured, saying "I can't do that
         * yet" beats silently doing half the job.
         *
         * The verb list matters: bare "and" would wrongly reject real names like
         * "Black and White" or "Sound and vibration".
         */
        const val CHAINED = "\\band (?:then )?(?:send|message|text|type|write|tap|click|press|search|play|call|reply|share|delete|turn|set|open|close)\\b"

        val RULES: List<CommandRule> = listOf(

            // --- Scheduling --------------------------------------------------------------
            // Registered before everything else because these *wrap* another command. The
            // flashlight rule matches "flashlight" anywhere, so "every day at 10 pm turn on
            // the flashlight" would otherwise switch the torch on immediately instead of
            // scheduling it. All three are anchored to their own opening words, so putting
            // them first shadows nothing.
            rule("routine-daily", "^every\\s+(?:day|morning|afternoon|evening|night)\\b") {
                val match = DAILY_ROUTINE.find(it.raw.trim()) ?: return@rule null
                val time = TimeOfDay.parse("at ${match.groupValues[1]}") ?: return@rule null
                val command = match.groupValues[2].trim().ifEmpty { return@rule null }

                listOf(
                    NovaAction.CreateRoutine(
                        trigger = RoutineTrigger.Daily(time),
                        command = command,
                        spokenSchedule = "every day at ${time.spoken()}",
                    ),
                )
            },

            // Two phrasings, two rules. Deriving which is which from one match was unreadable
            // and got it wrong.
            rule("reminder-to-at", "^remind me to\\b") {
                REMINDER_TO_AT.find(it.raw.trim())?.reminder(what = 1, time = 2)
            },

            rule("reminder-at-to", "^remind me at\\b") {
                REMINDER_AT_TO.find(it.raw.trim())?.reminder(what = 2, time = 1)
            },

            rule("routine-battery", "^(?:when|if)\\b.*\\bbattery\\b") {
                val match = BATTERY_ROUTINE.find(it.raw.trim()) ?: return@rule null
                val percent = match.groupValues[1].toIntOrNull()?.takeIf { p -> p in 1..99 }
                    ?: return@rule null
                val command = match.groupValues[2].trim().ifEmpty { return@rule null }

                listOf(
                    NovaAction.CreateRoutine(
                        trigger = RoutineTrigger.BatteryBelow(percent),
                        command = command,
                        spokenSchedule = "when the battery drops below $percent percent",
                    ),
                )
            },

            rule("routine-power", "^(?:when|if)\\b.*\\b(?:charger|charging|plug|unplug|power)\\b") {
                val match = POWER_ROUTINE.find(it.raw.trim()) ?: return@rule null
                val command = match.groupValues[2].trim().ifEmpty { return@rule null }

                val disconnected = Regex("un ?plug|disconnect|remove", RegexOption.IGNORE_CASE)
                    .containsMatchIn(match.groupValues[1])

                listOf(
                    NovaAction.CreateRoutine(
                        trigger = if (disconnected) {
                            RoutineTrigger.PowerDisconnected
                        } else {
                            RoutineTrigger.PowerConnected
                        },
                        command = command,
                        spokenSchedule = if (disconnected) {
                            "when you unplug the charger"
                        } else {
                            "when you plug in"
                        },
                    ),
                )
            },

            rule("say", "^say (.+)$") {
                val words = it.rawAfter("say") ?: it.group(1)
                words.takeIf(String::isNotBlank)?.let { text -> listOf(NovaAction.Speak(text)) }
            },

            simpleRule(
                "list-routines",
                "^(?:list|show|what are) (?:my )?routines$|^what have you scheduled$",
                NovaAction.ListRoutines,
            ),

            rule("delete-routine", "^(?:delete|remove|cancel|stop) (?:the )?(?:routine|reminder|alarm)(?: for| to| about)? ?(.*)$") {
                listOf(NovaAction.DeleteRoutine(it.group(1).trim()))
            },

            // --- Torch -------------------------------------------------------------------
            // Registered first among the direct commands: "close the torch" must not reach
            // the close-app rule.
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
            // Reading comes before opening: "read my notifications" and "show my
            // notifications" are different requests, and the shade rule would swallow both.
            simpleRule(
                "read-notifications",
                "\\bread (?:me |out )?(?:my |the )?notifications\\b|\\bwhat did i miss\\b|" +
                    "\\bany (?:new )?(?:messages|notifications)\\b|\\bcheck (?:my )?notifications\\b",
                NovaAction.ReadNotifications,
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
                if (it.contains(CHAINED)) return@rule null
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
                if (it.contains(CHAINED)) return@rule null
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

            // --- Memory ------------------------------------------------------------------
            // Registered last so nothing else is shadowed: "what is on screen" stays a screen
            // read and "who are you" stays an introduction, rather than becoming lookups.
            rule("remember", "^remember\\b") {
                val said = it.rawAfter("remember")?.removePrefix("that ")?.trim()
                    ?: return@rule null

                // Requires a subject and a fact. "Remember to buy milk" is a reminder, which
                // is a different feature with a different lifetime, and quietly filing it as
                // a fact would lose the part that matters — when.
                val (subject, detail) = said.splitSubjectAndDetail() ?: return@rule null
                listOf(NovaAction.Remember(subject, detail))
            },

            simpleRule(
                "recall-all",
                "^what do you remember\\b|^what have you remembered\\b|^list your memor(?:y|ies)$",
                NovaAction.RecallAll,
            ),

            rule("forget", "^forget (?:about )?(?:my |the |our )?(.+)$") {
                it.group(1).takeIf(String::isNotBlank)
                    ?.let { subject -> listOf(NovaAction.ForgetMemory(subject)) }
            },

            rule(
                "recall",
                "^(?:what|where|when|who|which)(?:s| is| was| are| were)? (?:my |the |our )?(.+)$",
            ) {
                val subject = it.group(1).trim()

                // A stored subject is a short noun phrase — "parking spot", "the gate code".
                // Without this cap the pattern swallows any sentence beginning with a question
                // word, including a scheduling command that declined earlier: "when battery is
                // below 0 percent, open settings" became a lookup for a seven-word subject
                // instead of being reported as not understood.
                if (subject.isBlank() || subject.split(' ').size > MAX_SUBJECT_WORDS) {
                    return@rule null
                }
                listOf(NovaAction.Recall(subject))
            },
        )

        /**
         * Splits "my parking spot is B2" into a subject and a fact.
         *
         * Works on the raw utterance so casing and punctuation survive — a door code or a
         * password is worthless once it has been lowercased and stripped.
         */
        fun String.splitSubjectAndDetail(): Pair<String, String>? {
            val separator = SUBJECT_SEPARATORS.firstNotNullOfOrNull { candidate ->
                indexOf(candidate, ignoreCase = true).takeIf { it > 0 }?.let { it to candidate }
            } ?: return null

            val (at, token) = separator
            val subject = substring(0, at).trim().removePrefix("that ").trim()
            val detail = substring(at + token.length).trim()

            return if (subject.isEmpty() || detail.isEmpty()) null else subject to detail
        }

        val SUBJECT_SEPARATORS = listOf(" is ", " are ", " was ", " were ", ": ")

        /** Longer than this and it is a sentence, not something Nova was told to remember. */
        const val MAX_SUBJECT_WORDS = 5

        /** A clock time as spoken: "6", "6:30", "6 pm", "18:00". */
        const val TIME = "\\d{1,2}(?::\\d{2})?(?:\\s*[ap]m)?"

        val DAILY_ROUTINE =
            Regex("^every\\s+(?:day|morning|afternoon|evening|night)\\s+at\\s+($TIME)\\s*,?\\s*(.+)$", RegexOption.IGNORE_CASE)

        val REMINDER_TO_AT =
            Regex("^remind me to\\s+(.+?)\\s+at\\s+($TIME)\\s*$", RegexOption.IGNORE_CASE)

        val REMINDER_AT_TO =
            Regex("^remind me at\\s+($TIME)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)

        val BATTERY_ROUTINE = Regex(
            "^(?:when|if)\\s+(?:the\\s+)?battery\\s+(?:is\\s+|gets\\s+|drops\\s+|falls\\s+)?" +
                // Three digits, not two: "below 100 percent" would otherwise capture "10" and
                // silently create a routine at ten percent with "0 percent" left in the
                // command, instead of being rejected as the nonsense it is.
                "(?:below|under|less than)\\s+(\\d{1,3})\\s*(?:%|percent)?\\s*,?\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )

        val POWER_ROUTINE = Regex(
            "^(?:when|if)\\s+(?:i\\s+)?(.*?(?:charger|charging|plug\\w*|power).*?)\\s*,\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Builds a one-off reminder from a match.
         *
         * Stored as the utterance "say <thing>", so a reminder is simply a scheduled command
         * and reuses the whole existing pipeline rather than needing a parallel one.
         */
        fun MatchResult.reminder(what: Int, time: Int): List<NovaAction>? {
            val thing = groupValues[what].trim().ifEmpty { return null }
            val at = TimeOfDay.parse("at ${groupValues[time]}") ?: return null

            return listOf(
                NovaAction.CreateRoutine(
                    trigger = RoutineTrigger.OnceAt(at),
                    command = "say $thing",
                    spokenSchedule = "at ${at.spoken()}",
                ),
            )
        }

        /** Picks the audio stream named in the utterance, defaulting to media. */
        fun streamIn(text: String): VolumeStream = when {
            Regex("\\bring(?:er|tone)?\\b|\\bnotification\\b").containsMatchIn(text) -> VolumeStream.RING
            Regex("\\balarm\\b").containsMatchIn(text) -> VolumeStream.ALARM
            Regex("\\b(?:in ?call|call|voice)\\b").containsMatchIn(text) -> VolumeStream.CALL
            else -> VolumeStream.MEDIA
        }
    }
}
