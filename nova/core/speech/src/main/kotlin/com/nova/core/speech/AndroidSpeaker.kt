package com.nova.core.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
) : Speaker {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, (Unit) -> Unit>()
    private val counter = AtomicLong()

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
    }

    override fun stop() {
        pending.keys.toList().forEach { pending.remove(it)?.invoke(Unit) }
        runCatching { tts.stop() }
    }

    override fun release() {
        stop()
        runCatching { tts.shutdown() }
    }
}
