package com.nova.core.speech

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Fires whenever the wake phrase is heard. */
interface WakeWordDetector {

    val phrase: String

    /** Cold flow — collecting starts listening, cancelling stops it. */
    fun detections(): Flow<Unit>
}

/**
 * Wake word by re-running the speech recogniser and looking for the phrase in the transcript.
 *
 * This is deliberately the cheap version. It works today with no extra dependency and no model
 * file, which is what Phase 1 needs, but it holds the microphone continuously and costs real
 * battery — the platform recogniser is not built to run all day.
 *
 * The Phase 2 replacement is a dedicated keyword spotter (Porcupine, or openWakeWord) running a
 * few-hundred-kilobyte model on a small audio ring buffer. That is a new implementation of this
 * same interface; the service that collects [detections] does not change.
 */
class TranscriptWakeWordDetector(
    private val speechToText: SpeechToText,
    override val phrase: String = "nova",
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
