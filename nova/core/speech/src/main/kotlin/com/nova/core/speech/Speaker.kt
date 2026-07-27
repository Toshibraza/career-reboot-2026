package com.nova.core.speech

/** One voice the engine can speak with. */
data class VoiceOption(
    val id: String,
    /** Human-readable, e.g. "English (India) · 3". */
    val label: String,
    val offline: Boolean,
    /** True when it matches the heuristic for a male voice — a hint, not a guarantee. */
    val likelyMale: Boolean,
)

/** Raza's voice. */
interface Speaker {

    /** Voices for the current language, best first. Empty until the engine has initialised. */
    suspend fun voices(): List<VoiceOption>

    /** Switches voice. Null returns to automatic selection. */
    suspend fun useVoice(id: String?)

    /**
     * Speaks [text] and suspends until the device has finished saying it.
     *
     * Suspending until done is what makes turn-taking work: the caller can re-open the mic on
     * the next line without Nova hearing itself.
     */
    suspend fun speak(text: String, interrupt: Boolean = true)

    /** Stops mid-sentence. */
    fun stop()

    /** Releases the engine. Call from the owner's onDestroy. */
    fun release()
}
