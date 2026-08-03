package com.nova.core.speech

import android.util.Log
import com.nova.core.agent.match.FuzzyMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** Fires whenever the wake phrase is heard. */
interface WakeWordDetector {

    val phrase: String

    /** Cold flow — collecting starts listening, cancelling stops it. */
    fun detections(): Flow<Unit>
}

/**
 * Wake word in two stages: a cheap gate, then a confirmation.
 *
 * [VoiceActivityGate] watches raw microphone energy, which is arithmetic over a small buffer.
 * Only when it hears a voice does the speech recogniser start, and only to answer one question:
 * was that the wake phrase. In a quiet room the recogniser stays off entirely, which is the
 * difference between this and running an ASR pipeline all day.
 *
 * The two never hold the microphone at once — the gate's flow is collected only until it fires,
 * released, and re-collected after the recogniser is done.
 *
 * ### The honest limitation
 *
 * This still runs full speech recognition on every voice it hears, so a room with a
 * conversation in it wakes the recogniser repeatedly. A real keyword spotter — Porcupine, or
 * openWakeWord — runs a purpose-trained model of a megabyte or two over a rolling audio buffer
 * and answers "was that the phrase" without transcribing anything. That is the correct fix, and
 * it is a new implementation of this interface: nothing that collects [detections] changes.
 */
class GatedWakeWordDetector(
    private val speechToText: SpeechToText,
    /**
     * Null runs recognition continuously instead of waiting for speech onset.
     *
     * The gate cannot hear the word that triggers it. It detects the onset, releases the
     * microphone, and the recogniser takes over — measured at ~390ms on the target phone even
     * with the engine kept warm, and over a second before it was. "Raza" is finished inside
     * that window, so the recogniser only ever received the silence afterwards and reported
     * NO_MATCH. Every wake attempt failed this way; the log is unambiguous.
     *
     * Continuous recognition costs battery, which is exactly what the gate existed to save.
     * That trade only made sense while the gate worked. A purpose-built keyword spotter reading
     * the gate's own PCM is the real answer and remains the honest limitation below — the
     * platform recogniser cannot be fed buffered audio, so the onset cannot be replayed to it.
     */
    private val gate: VoiceActivityGate? = VoiceActivityGate(),
    override val phrase: String = "raza",
    private val languageTag: String = "en-IN",
    /** Breathing room so one holder has fully released the microphone before the next opens it. */
    private val handoverDelayMillis: Long = 250,
    /**
     * Discards a "wake phrase" that is really Raza's own reply coming back.
     *
     * Null disables the check, which is only appropriate where nothing is being spoken.
     */
    private val echoGuard: EchoGuard? = null,
) : WakeWordDetector {

    private companion object {
        const val TAG = "NovaWake"

        /**
         * How close a heard word must be to count as the wake word.
         *
         * Below [com.nova.core.agent.match.FuzzyMatcher.MIN_SCORE] on purpose. A personal name
         * is not in the recogniser's vocabulary, so it never comes back cleanly — "Raza"
         * arrives as "razor" (score 36) or "rasa" (45), and a threshold tuned for app names
         * rejects both. Set low enough to accept those, high enough to reject "raise" (24)
         * and ordinary conversation.
         */
        const val WAKE_SCORE = 35
    }

    /**
     * Whether [text] contains something close enough to the wake phrase.
     *
     * Compared word by word with the same fuzzy scoring used for app names and contacts,
     * rather than against a hand-written list of spellings. A list only ever covers the
     * mishearings someone thought of; scoring covers the ones they did not.
     */
    private fun containsWakeWord(text: String): Boolean {
        val target = FuzzyMatcher.normalise(phrase)

        return text.split(Regex("\\W+"))
            .filter { it.isNotBlank() }
            .any { word -> FuzzyMatcher.score(FuzzyMatcher.normalise(word), target) >= WAKE_SCORE }
    }

    override fun detections(): Flow<Unit> = flow {
        Log.i(TAG, "wake word active, listening for \"$phrase\"")

        while (true) {
            // Collected only until the first speech onset, so the gate releases the microphone
            // before the recogniser needs it.
            gate?.let {
                it.speechStarts().first()
                delay(handoverDelayMillis)
            }

            if (heardWakePhrase()) {
                Log.i(TAG, "wake phrase heard")
                emit(Unit)
            }
            delay(handoverDelayMillis)
        }
    }

    private suspend fun heardWakePhrase(): Boolean {
        var heard = false
        var lastHeard = ""
        var echoed = false

        speechToText.transcribe(languageTag).collect { event ->
            // Every candidate, not just the winner — the wake word is frequently a runner-up.
            val candidates = when (event) {
                is SpeechEvent.Partial -> listOf(event.text)
                is SpeechEvent.Final -> listOf(event.text) + event.alternatives
                is SpeechEvent.Failed -> {
                    Log.i(TAG, "recogniser gave up: ${event.reason}")
                    emptyList()
                }
                else -> emptyList()
            }

            candidates.firstOrNull()?.let { text ->
                lastHeard = text
                if (echoGuard?.isEcho(text) == true) echoed = true
            }
            if (!heard && candidates.any(::containsWakeWord)) heard = true
        }

        // Checked after collecting rather than instead of it, so the log still shows what was
        // heard. Otherwise "it woke itself up" is indistinguishable from "it woke up".
        if (heard && echoed) {
            Log.i(TAG, "ignored \"$lastHeard\" — that was Raza hearing itself")
            return false
        }

        // The single most useful line when the wake word "doesn't work": it usually did hear
        // something, just not the phrase.
        if (!heard && lastHeard.isNotBlank()) {
            Log.i(TAG, "heard \"$lastHeard\" but it did not contain \"$phrase\"")
        }
        return heard
    }
}

/**
 * The original Phase 1 detector: re-runs the recogniser in a loop whether or not anyone is
 * speaking.
 *
 * Kept only as a fallback for devices where [VoiceActivityGate] cannot open the microphone.
 * It works, and it costs real battery — prefer [GatedWakeWordDetector].
 */
class TranscriptWakeWordDetector(
    private val speechToText: SpeechToText,
    override val phrase: String = "raza",
    private val languageTag: String = "en-IN",
    /** Pause between sessions, so a failing recogniser can't spin at 100% CPU. */
    private val retryDelayMillis: Long = 400,
) : WakeWordDetector {

    private val needle = Regex("\\b${Regex.escape(phrase.lowercase())}\\b")

    override fun detections(): Flow<Unit> = flow {
        while (true) {
            var heard = false

            speechToText.transcribe(languageTag).collect { event ->
                val text = when (event) {
                    is SpeechEvent.Partial -> event.text
                    is SpeechEvent.Final -> event.text
                    else -> null
                }
                if (!heard && text != null && needle.containsMatchIn(text.lowercase())) {
                    heard = true
                }
            }

            if (heard) emit(Unit)
            delay(retryDelayMillis)
        }
    }
}
