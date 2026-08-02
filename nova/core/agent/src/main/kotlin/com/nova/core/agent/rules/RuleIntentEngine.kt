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

        /**
         * Words meaning "raise" and "lower", shared by the volume and brightness rules.
         *
         * Hinglish sits alongside English rather than in a separate engine. People switch
         * languages mid-sentence — "volume thoda badhao" is one utterance, not two — and the
         * recogniser already runs at en-IN, so romanised Hindi is what it returns. Splitting
         * the two would mean deciding which language a sentence is in before parsing it, which
         * is both harder and wrong for how it is actually spoken.
         */
        const val UP = "\\b(?:up|increase|raise|louder|higher|brighter|" +
            "badhao|badha ?do|badhaao|tez karo|zyada karo)\\b"

        const val DOWN = "\\b(?:down|decrease|lower|reduce|quieter|softer|dimmer|dim|" +
            "kam karo|kam kar ?do|ghatao|ghata ?do|dheere karo|halka karo)\\b"

        /**
         * "Band" is the load-bearing word here — it is how almost everything gets switched off
         * in spoken Hinglish, and it collides with nothing else this engine matches. It is
         * only ever consulted once a noun like "torch" has already been recognised.
         */
        const val OFF = "\\b(?:off|disable|stop|kill|close|shut|" +
            "band|bandh|bujha ?do|bujhao)\\b"

        /**
         * Nouns, kept separate from the verbs because word order differs between the two
         * languages and only the nouns can be matched positionally.
         */
        const val VOLUME_NOUN = "\\b(?:volume|awaaz|aawaz|awaz|avaaz)\\b"
        const val BRIGHTNESS_NOUN = "\\b(?:bright(?:ness)?|roshni|chamak)\\b"
        const val TORCH_NOUN = "\\b(?:flash ?light|torch|batti|tarch)\\b"

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

            // --- Reaching people ---------------------------------------------------------
            // Before the app rules: "call Mom" is a person, not an app called Mom. Both of
            // these only ever propose — nothing here dials or sends.
            rule("send-sms", "^(?:text|message|sms|send)\\b") {
                // Marker form first ("text Amit saying I'm late"), because it separates the
                // name from the message unambiguously. The bare form ("text Amit I'm late")
                // has to guess where the name ends, so it only accepts a single-word name —
                // and a wrong guess there surfaces as "I couldn't find a contact called my"
                // rather than a message to the wrong person.
                val match = SMS_WITH_MARKER.find(it.raw.trim())
                    ?: SMS_SINGLE_WORD_NAME.find(it.raw.trim())
                    ?: return@rule null

                val who = match.groupValues[1].trim()
                val what = match.groupValues[2].trim()

                if (who.isBlank() || what.isBlank()) return@rule null
                listOf(NovaAction.SendSms(who, what))
            },

            rule("call", "^(?:call|phone|ring|dial)\\s+(.+)$") {
                // From the raw utterance, so a name keeps its capitals. It is read back to
                // the user before dialling, and "call amit kumar?" looks like a bug.
                val who = (it.rawAfter("call", "phone", "ring", "dial") ?: it.group(1)).trim()
                // "call back" and "call again" name no one, and guessing who would be the
                // worst possible interpretation.
                if (who.isBlank() || who.lowercase() in AMBIGUOUS_CALL_TARGETS) return@rule null
                listOf(NovaAction.CallContact(who))
            },

            // The same two intents with the words the other way round. Hindi puts the object
            // first and the verb last — "Amit ko call karo" is literally "Amit to call do" —
            // so these cannot be folded into the rules above by adding alternatives; the
            // capture group has to move.
            rule("send-sms-hinglish", "\\bko\\b.*\\b(?:message|msg|sms|text)\\b") {
                val match = SMS_HINGLISH.find(it.raw.trim()) ?: return@rule null
                val who = match.groupValues[1].trim()
                val what = match.groupValues[2].trim()

                if (who.isBlank() || what.isBlank()) return@rule null
                listOf(NovaAction.SendSms(who, what))
            },

            rule("call-hinglish", "\\bko\\b.*\\b(?:call|phone|fon|milao)\\b") {
                // Raw, so a name keeps its capitals — it is read back before dialling, and
                // "call amit kumar?" looks like a bug.
                val who = CALL_HINGLISH.find(it.raw.trim())?.groupValues?.get(1)?.trim()
                    ?: return@rule null

                if (who.isBlank() || who.lowercase() in AMBIGUOUS_CALL_TARGETS) return@rule null
                listOf(NovaAction.CallContact(who))
            },

            simpleRule(
                "confirm",
                "^(?:yes|yeah|yep|confirm|do it|go ahead|send it|call (?:them|him|her)|" +
                    "haan|haa|ha|theek hai|kar do|karo)$",
                NovaAction.ConfirmPending,
            ),

            simpleRule(
                "cancel",
                "^(?:no|nope|cancel|stop|never mind|nevermind|forget it|" +
                    "nahi|nahin|na|rehne do|mat karo|chodo)$",
                NovaAction.CancelPending,
            ),

            // --- Torch -------------------------------------------------------------------
            // Registered first among the direct commands: "close the torch" must not reach
            // the close-app rule.
            rule("flashlight", TORCH_NOUN) {
                listOf(NovaAction.SetFlashlight(on = !it.contains(OFF)))
            },

            // --- Volume ------------------------------------------------------------------
            // The third alternative carries Hinglish, which states a percentage with no
            // preposition at all — "awaaz 40 percent karo". Matching a bare number instead
            // would swallow "volume up 10" and turn a nudge into an absolute setting.
            rule(
                "volume-set",
                "$VOLUME_NOUN.*\\b(?:to|at|par|pe)\\b|\\bset\\b.*$VOLUME_NOUN|" +
                    "$VOLUME_NOUN.*\\d+\\s*(?:%|percent|pratishat)",
            ) {
                val percent = Numbers.firstIn(it.text) ?: return@rule null
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Absolute(percent)))
            },

            rule("volume-max", "\\b(?:max|maximum|full|pura|poora)\\b") {
                if (!it.contains(VOLUME_NOUN)) return@rule null
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Max))
            },

            // Silencing is switching the volume off, in both languages. Without the OFF arm,
            // "awaaz band karo" fell past every volume rule and was read as a request to close
            // an app called "awaaz" — and "turn off the volume" was not understood at all.
            // Both orders, because the two languages put the noun on opposite sides of the
            // verb: "awaaz band karo" against "turn off the volume".
            rule(
                "volume-mute",
                "\\b(?:mute|silence|silent|chup)\\b|$VOLUME_NOUN.*$OFF|$OFF.*$VOLUME_NOUN",
            ) {
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Min))
            },

            rule("volume-step", "$VOLUME_NOUN|\\b(?:louder|quieter|softer)\\b") {
                val delta = when {
                    it.contains(UP) -> 10
                    it.contains(DOWN) -> -10
                    else -> return@rule null
                }
                listOf(NovaAction.SetVolume(streamIn(it.text), LevelChange.Relative(delta)))
            },

            // --- Brightness --------------------------------------------------------------
            rule("brightness-set", "$BRIGHTNESS_NOUN.*\\b(?:to|at)\\b|\\bset\\b.*$BRIGHTNESS_NOUN") {
                val percent = Numbers.firstIn(it.text) ?: return@rule null
                listOf(NovaAction.SetBrightness(LevelChange.Absolute(percent)))
            },

            rule("brightness-step", "$BRIGHTNESS_NOUN|\\bdim\\b") {
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

            // Before read-screen: "read the text on screen" is a request to recognise pixels,
            // while "read the screen" is a request to list what can be tapped.
            simpleRule(
                "read-screen-text",
                "\\bread (?:me |out )?(?:the |this )?text\\b|\\bwhat does (?:this|it) say\\b|" +
                    "\\bread (?:this|the) (?:document|bill|receipt|label|page|image|photo)\\b|" +
                    "\\bscan (?:this|the screen)\\b",
                NovaAction.ReadScreenText,
            ),

            simpleRule(
                "read-screen",
                // "what does this say" belongs to the OCR rule above — it is a question about
                // text, not about which controls are on screen.
                "\\bwhat(?:s| is)? on (?:the |this )?screen\\b|\\bread (?:the |this )?screen\\b|\\bwhat (?:do you |can you )?see\\b",
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

            // Before the app rules, because "run" is one of their launch verbs — "run
            // diagnostics" was being read as a request to open an app called "diagnostics".
            simpleRule(
                "diagnostics",
                "\\b(?:diagnostics|self check|selfcheck)\\b|\\bwhat(?:s| is) wrong\\b|" +
                    "\\bare you (?:ok|okay|working)\\b|\\bcheck yourself\\b|\\bstatus report\\b",
                NovaAction.RunDiagnostics,
            ),

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

            // Object first, verb last. Registered after the English forms and after every
            // device rule, so "torch band karo" is still the torch rather than an app called
            // "torch", exactly as "close the torch" already was.
            rule("close-app-hinglish", "^(.+?)\\s+(?:band|bandh)\\s+kar(?:o|do| do| dijiye)$") {
                it.group(1).takeIf(String::isNotBlank)?.let { app -> listOf(NovaAction.CloseApp(app)) }
            },

            rule(
                "open-app-hinglish",
                "^(.+?)\\s+(?:kholo|khol ?do|kholiye|chalu kar(?:o|do| do)|" +
                    "shuru kar(?:o|do| do)|open kar(?:o|do| do))$",
            ) {
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
                NovaAction.Speak("I'm Raza, your assistant on this phone."),
            ),

            // --- Help ----------------------------------------------------------------------
            // Ahead of conversation, which would otherwise answer "what can you do" from the
            // model — slowly, and with whatever it imagines rather than what is built.
            // Matched against the *normalised* utterance, which is why "what do" appears here
            // and "what can you do" does not. Utterance.normalise strips "can you" as
            // politeness, so the sentence a user actually says never reaches a rule intact.
            //
            // Anchored end to end for the same reason: an unanchored "what do" would swallow
            // "what do you think about X", which is conversation.
            simpleRule(
                "capabilities",
                "^(?:help|commands|show( me)? (the )?commands|" +
                    "what do|what all do|what do you do|what (?:all )?can i (?:say|ask)( you)?|" +
                    "(?:tum |aap )?kya kar sakte ho|(?:tum |aap )?kya kar sakti ho)$",
                NovaAction.ListCapabilities,
            ),

            // --- Media ---------------------------------------------------------------------
            // Before the app rules: "play Coke Studio" names a thing to watch, not an app
            // called "Coke Studio". Both languages, and both orders.
            rule("play", "^play\\s+(.+)$|^(.+?)\\s+(?:chala ?do|chalao|baja ?do|bajao)$") {
                if (it.contains(CHAINED)) return@rule null
                // group() yields "" for the alternative that did not match, never null, so
                // each candidate has to be tested for content rather than for nullity.
                val query = (
                    it.rawAfter("play")
                        ?: it.group(1).takeIf(String::isNotBlank)
                        ?: it.group(2).takeIf(String::isNotBlank)
                    )?.trim()

                query?.takeIf(String::isNotBlank)?.let { q -> listOf(NovaAction.PlayMedia(q)) }
            },

            // --- Web search --------------------------------------------------------------
            // Explicit verbs only. "What is X" deliberately stays a memory lookup: asking
            // Raza what it knows should never quietly become a web request.
            rule(
                "web-search",
                "^(?:search(?: the web)?(?: for)?|google|look up|find out about)\\s+(.+)$",
            ) {
                val query = it.rawAfter(
                    "search the web for", "search the web", "search for", "search",
                    "google", "look up", "find out about",
                ) ?: it.group(1)

                query.takeIf(String::isNotBlank)?.let { q -> listOf(NovaAction.SearchWeb(q.trim())) }
            },

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

            // --- Conversation ------------------------------------------------------------
            // Dead last, so every capability above still wins. This is the "just talk to me"
            // path: questions and requests for an explanation, which no amount of tapping
            // around the phone can answer.
            //
            // Only shapes that are unmistakably conversational are matched. Anything else that
            // went unrecognised stays Unsupported and escalates to the task planner, which is
            // what drives multi-step work like "send Amit a message on WhatsApp". Sending
            // those here instead would trade a device that does things for one that talks
            // about doing them.
            rule("chat", CHAT_OPENERS) { listOf(NovaAction.Converse(it.group(0).trim())) },
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

        /**
         * Utterances that are plainly conversation rather than an instruction.
         *
         * "Why", "how" and "should" are here while "what" and "who" are not: those two are
         * claimed by the recall rule above, and a memory miss falls through to conversation in
         * [com.nova.core.agent.AgentRuntime] anyway. Catching them here as well would mean
         * "what is my parking spot" never reached memory at all.
         *
         * Kept to openers, never a bare keyword match. "How" anywhere in a sentence would
         * swallow "set brightness to however bright it goes"; anchored at the start it does
         * not.
         */
        const val CHAT_OPENERS =
            "^(?:" +
                "why\\b|how (?:do|does|did|can|could|would|should|is|are|long|many|much)\\b|" +
                "should i\\b|do you (?:know|think)\\b|what do you think\\b|" +
                "tell me\\b|explain\\b|describe\\b|define\\b|summarise\\b|summarize\\b|" +
                "give me\\b" +
                ").*"

        /** Phrases that follow "call" without naming anybody. */
        val AMBIGUOUS_CALL_TARGETS = setOf("back", "again", "them", "him", "her", "someone")

        val SMS_WITH_MARKER = Regex(
            "^(?:text|message|sms|send)\\s+(?:a\\s+(?:text|message)\\s+to\\s+)?(.+?)\\s+" +
                "(?:saying|that says|:)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )

        val SMS_SINGLE_WORD_NAME = Regex(
            "^(?:text|message|sms)\\s+(\\S+)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * "Amit ko message bhejo ki main late hoon."
         *
         * The content marker is required, as it is in English. Without "ki" or "bolo ki" there
         * is nothing separating the name from the message, and a wrong split there sends a
         * real message to the wrong person.
         */
        val SMS_HINGLISH = Regex(
            "^(.+?)\\s+ko\\s+(?:message|msg|sms|text)\\s+(?:bhej(?:o|do| do)|kar(?:o|do| do))" +
                "\\s+(?:ki|kah(?:o|do) ki|bol(?:o|do| do) ki)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * "Amit ko call karo", "Mummy ko phone lagao", "Amit ko milao".
         *
         * One capture group across every form on purpose — two alternatives each with their
         * own group would leave the caller reading an empty string whenever the other branch
         * matched.
         */
        val CALL_HINGLISH = Regex(
            "^(.+?)\\s+ko\\s+(?:(?:call|phone|fon)\\s+(?:kar(?:o|do| do)|lag(?:ao|a ?do))|milao)$",
            RegexOption.IGNORE_CASE,
        )

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
