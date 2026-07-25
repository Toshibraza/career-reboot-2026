package com.nova.core.speech

/** Nova's voice. */
interface Speaker {

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
