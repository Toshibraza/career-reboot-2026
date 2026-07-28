package com.nova.core.speech

/**
 * Rejects transcripts that are Raza hearing itself.
 *
 * The phone's speaker and microphone are centimetres apart, so anything spoken aloud is also
 * heard. Turn-taking already prevents most of it — [Speaker.speak] suspends until the device has
 * finished — but "finished" is when the engine stops writing audio, not when the room stops
 * carrying it. A reverberant tail, a Bluetooth buffer, or a slow speaker leaves a second or so
 * where the next thing the microphone picks up is the last thing Raza said.
 *
 * Unhandled, this is a feedback loop: Raza answers, hears the answer, treats it as a command,
 * answers again. Always-listening is where it bites, because nothing there needs a human present
 * to keep it going.
 *
 * Adapted from isair/jarvis, which fights the same problem on the desktop. Most of its logic is
 * about salvaging a user's words from a transcript that begins with echo — worth it when the
 * assistant narrates long answers and people talk over it. Raza's replies are one line, so the
 * useful part is the decision underneath: compare what was heard against what was just said, and
 * throw it away if they are the same thing.
 */
class EchoGuard(
    /**
     * How long after speaking a transcript is still suspect.
     *
     * Covers the speaker's decay and the recogniser's own latency. Beyond this the user has had
     * time to reply, and rejecting them would be worse than the occasional echo getting through.
     */
    private val windowMillis: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var lastSpoken: String = ""

    @Volatile
    private var finishedAt: Long = 0

    /** Called when Raza has finished saying [text]. */
    fun spoke(text: String) {
        lastSpoken = text
        finishedAt = clock()
    }

    /** Forgets the last utterance, so an unrelated later transcript is judged on its own. */
    fun clear() {
        lastSpoken = ""
        finishedAt = 0
    }

    fun isEcho(heard: String): Boolean {
        if (lastSpoken.isBlank() || heard.isBlank()) return false
        if (clock() - finishedAt > windowMillis) return false

        val heardWords = words(heard)

        // A one- or two-word utterance is a barge-in, not an echo: "stop", "cancel", "no",
        // "louder". Those are the moments a user most needs to be heard, and they are also
        // short enough to collide with the reply by chance — "stop" appears in plenty of
        // sentences. Refusing to judge them is the safer error.
        if (heardWords.size < MIN_WORDS_TO_JUDGE) return false

        val spokenWords = words(lastSpoken).toSet()
        val shared = heardWords.count { it in spokenWords }

        return shared.toFloat() / heardWords.size >= MIN_COVERAGE
    }

    /**
     * Compared as a bag of words rather than a sequence.
     *
     * The recogniser drops and reorders words in a way a substring check cannot survive: "I've
     * opened YouTube for you" comes back as "opened YouTube for you" or "I opened you tube".
     * Asking how much of what was heard also appears in what was said tolerates all of that,
     * and a genuine command shares almost nothing with the previous reply.
     */
    private fun words(text: String): List<String> =
        text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotBlank() }

    private companion object {
        /** Below this an utterance is treated as a barge-in and always allowed through. */
        const val MIN_WORDS_TO_JUDGE = 3

        /**
         * Fraction of heard words that must also appear in the reply.
         *
         * Set from what the failure looks like rather than from theory: an echo is nearly all
         * reply words, a real command is nearly none, and the gap between them is wide enough
         * that the exact figure barely matters. Erring low would silence the user.
         */
        const val MIN_COVERAGE = 0.7f
    }
}
