package com.nova.core.speech

import kotlinx.coroutines.flow.Flow

/** Something that happened during one listening session. */
sealed interface SpeechEvent {

    /** The mic is open and the user can start talking. */
    data object Listening : SpeechEvent

    /** Mic amplitude in dB, for the UI's level meter. Emitted frequently — don't log it. */
    data class Level(val db: Float) : SpeechEvent

    /** Best guess so far. Shown live but never acted on. */
    data class Partial(val text: String) : SpeechEvent

    /** The recogniser has settled. This is what gets sent to the agent. */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val reason: SpeechError) : SpeechEvent
}

enum class SpeechError {
    /** Heard audio but couldn't make words out of it. */
    NO_MATCH,

    /** Silence until the recogniser gave up. */
    TIMEOUT,

    /** RECORD_AUDIO not granted. */
    PERMISSION_DENIED,

    /** Recogniser needs the network and didn't have it. */
    NETWORK,

    /** Another app holds the mic, or a session is already running. */
    BUSY,

    /** No recognition service installed on the device at all. */
    UNAVAILABLE,

    UNKNOWN,
}

/**
 * Speech recognition, one session at a time.
 *
 * The Phase 1 implementation wraps Android's [android.speech.SpeechRecognizer]. Swapping in
 * Whisper or Vosk for offline transcription means implementing this interface and changing one
 * line at the composition root — nothing else in the app touches a recogniser.
 */
interface SpeechToText {

    /** False when no recognition service is installed. Check before offering the mic. */
    val isAvailable: Boolean

    /**
     * Opens the mic and emits until a [SpeechEvent.Final] or [SpeechEvent.Failed] arrives.
     * Cancelling the collection releases the mic.
     */
    fun transcribe(languageTag: String = "en-IN"): Flow<SpeechEvent>
}
