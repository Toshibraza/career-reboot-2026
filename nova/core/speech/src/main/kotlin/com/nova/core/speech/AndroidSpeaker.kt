package com.nova.core.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * [Speaker] backed by the platform TTS engine.
 *
 * The engine takes a moment to initialise, so [speak] awaits [ready] rather than dropping early
 * lines on the floor — the first thing Nova says after launch is usually the most important one.
 */
class AndroidSpeaker(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
    /** Voice chosen by the user, if any. Overrides the heuristic entirely. */
    private val preferredVoiceId: () -> String? = { null },
    /**
     * Told what was said, and when, so the microphone can discard hearing it back.
     *
     * Recorded here rather than by callers: every route that speaks needs this, and one that
     * forgot would reintroduce the feedback loop silently.
     */
    private val echoGuard: EchoGuard? = null,
) : Speaker {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, (Unit) -> Unit>()
    private val counter = AtomicLong()

    @Volatile
    private var voiceChosen = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.invoke(Unit) }
            }

            @Deprecated("Required by the platform base class.")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.invoke(Unit) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.invoke(Unit) }
            }
        })
    }

    override suspend fun speak(text: String, interrupt: Boolean) {
        if (text.isBlank()) return
        if (!ready.await()) return

        // Set on every call rather than once at init: the engine reports available languages
        // only after it is ready, and the user can change locale while the app is alive.
        runCatching { tts.language = locale }
        selectPreferredVoice()

        val id = "nova-${counter.incrementAndGet()}"
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        suspendCancellableCoroutine { continuation ->
            pending[id] = { if (!continuation.isCompleted) continuation.resume(Unit) }

            continuation.invokeOnCancellation {
                pending.remove(id)
                runCatching { tts.stop() }
            }

            val result = tts.speak(text, queueMode, null, id)
            if (result != TextToSpeech.SUCCESS) {
                pending.remove(id)
                if (!continuation.isCompleted) continuation.resume(Unit)
            }
        }

        // After the wait, not before: the window the guard measures runs from the moment the
        // sound stopped, which is the moment the room starts echoing it.
        echoGuard?.spoke(text)
    }

    /**
     * Picks a male voice, preferring one matching the user's exact locale.
     *
     * Android's `Voice` has no gender field, and Google's engine encodes nothing useful in the
     * name — its identifiers are opaque three-letter codes like `en-in-x-end` and
     * `en-us-x-iom`. So gender comes from [MALE_VOICE_IDS], which is empirical rather than
     * derived, and is the part of this most likely to be wrong on an unfamiliar engine.
     *
     * Engines that *do* put "male" in the name are still handled, so this works on more than
     * just Google's.
     *
     * Chosen once and cached: enumerating voices on every utterance is wasteful and the set
     * does not change while the app runs.
     */
    private fun selectPreferredVoice() {
        if (voiceChosen) return
        voiceChosen = true

        val all = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == locale.language }

        // An explicit choice wins outright. Gender cannot be read from the engine, so a user
        // who has heard the voices knows better than any heuristic here.
        preferredVoiceId()?.let { chosen ->
            all.firstOrNull { it.name == chosen }?.let { voice ->
                runCatching { tts.voice = voice }
                Log.i(TAG, "using chosen voice ${voice.name}")
                return
            }
        }

        val male = all
            .filter { it.name.looksMale() }
            .minByOrNull { voice ->
                var rank = 0
                // The user's own region first — an Indian English speaker should not be
                // answered in an American accent when a local voice exists.
                if (voice.locale.country != locale.country) rank += 100
                // Then offline, since everything else in Raza works without a network.
                if (voice.isNetworkConnectionRequired) rank += 10
                rank - voice.quality / 100
            }

        if (male != null) {
            runCatching { tts.voice = male }
            Log.i(TAG, "using voice ${male.name}")
        } else {
            Log.i(
                TAG,
                "no known male voice for ${locale.language}; candidates were " +
                    all.take(MAX_LOGGED_VOICES).map { it.name },
            )
        }
    }

    override suspend fun voices(): List<VoiceOption> {
        if (!ready.await()) return emptyList()

        return runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == locale.language }
            .sortedWith(
                compareBy(
                    { it.locale.country != locale.country },
                    { it.isNetworkConnectionRequired },
                    { it.name },
                ),
            )
            .map { voice ->
                VoiceOption(
                    id = voice.name,
                    label = "${voice.locale.displayName} · ${voice.name.substringAfterLast('-', voice.name)}",
                    offline = !voice.isNetworkConnectionRequired,
                    likelyMale = voice.name.looksMale(),
                )
            }
    }

    override suspend fun useVoice(id: String?) {
        if (!ready.await()) return

        if (id == null) {
            // Back to automatic: clear the cache so the heuristic runs again next utterance.
            voiceChosen = false
            return
        }

        runCatching { tts.voices }.getOrNull().orEmpty()
            .firstOrNull { it.name == id }
            ?.let {
                runCatching { tts.voice = it }
                voiceChosen = true
                Log.i(TAG, "switched to $id")
            }
    }

    override fun stop() {
        pending.keys.toList().forEach { pending.remove(it)?.invoke(Unit) }
        runCatching { tts.stop() }
    }

    override fun release() {
        stop()
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val TAG = "NovaSpeech"
        const val MAX_LOGGED_VOICES = 60

        /**
         * Google TTS voice codes that are male.
         *
         * Empirical, not derived — Google publishes no gender for the on-device voices, and
         * the identifiers carry no hint. Matched as a fragment so both the `-local` and
         * `-network` variants of each are covered.
         *
         * If a voice here turns out to be female on some engine, this list is the one place
         * to correct it.
         */
        val MALE_VOICE_IDS = setOf(
            // Indian English. "enc" was listed here and turned out to be female on a real
            // device — which is precisely why the user can now override this entirely.
            "en-in-x-end", "en-in-x-ena",
            // US English
            "en-us-x-iob", "en-us-x-iog", "en-us-x-iol", "en-us-x-iom",
            // British English
            "en-gb-x-gbd", "en-gb-x-rjs",
            // Australian English
            "en-au-x-aub", "en-au-x-aud",
        )

        /**
         * Whether a voice name denotes a male voice.
         *
         * The "female" check has to come first — it contains "male" as a substring, so a naive
         * `contains("male")` matches every female voice too. That is a real bug this ordering
         * exists to avoid, and it applies to engines that name voices honestly; Google's do
         * not, which is why [MALE_VOICE_IDS] exists.
         */
        fun String.looksMale(): Boolean {
            val name = lowercase()
            if (MALE_VOICE_IDS.any { it in name }) return true
            if ("female" in name || "fem" in name) return false
            return "male" in name || "#m" in name || "_m_" in name
        }
    }
}
