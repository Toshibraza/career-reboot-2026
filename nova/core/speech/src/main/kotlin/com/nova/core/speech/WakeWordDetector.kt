package com.nova.core.speech

import android.util.Log
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
    private val gate: VoiceActivityGate = VoiceActivityGate(),
    override val phrase: String = "raza",
    private val languageTag: String = "en-IN",
    /** Breathing room so one holder has fully released the microphone before the next opens it. */
    private val handoverDelayMillis: Long = 250,
) : WakeWordDetector {

    /**
     * The wake phrase and the near-misses a recogniser produces for it.
     *
     * A personal name is not in the recogniser's vocabulary, so it comes back as whatever real
     * word sounds closest — "razor", "rasa", "raise a". Matching only the exact spelling is why
     * a wake word appears not to work when it is being heard perfectly well.
     */
    private val needle = Regex(
        "\\b(?:${(listOf(phrase) + HOMOPHONES).joinToString("|") { Regex.escape(it.lowercase()) }})\\b",
    )

    private companion object {
        const val TAG = "NovaWake"

        val HOMOPHONES = listOf("razor", "rasa", "razaa", "rezza", "raiser", "raza's")
    }

    override fun detections(): Flow<Unit> = flow {
        Log.i(TAG, "wake word active, listening for \"$phrase\"")

        while (true) {
            // Collected only until the first speech onset, so the gate releases the microphone
            // before the recogniser needs it.
            gate.speechStarts().first()
            delay(handoverDelayMillis)

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

        speechToText.transcribe(languageTag).collect { event ->
            val text = when (event) {
                is SpeechEvent.Partial -> event.text
                is SpeechEvent.Final -> event.text
                is SpeechEvent.Failed -> {
                    Log.i(TAG, "recogniser gave up: ${event.reason}")
                    null
                }
                else -> null
            }
            if (text != null) lastHeard = text
            if (!heard && text != null && needle.containsMatchIn(text.lowercase())) {
                heard = true
            }
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
