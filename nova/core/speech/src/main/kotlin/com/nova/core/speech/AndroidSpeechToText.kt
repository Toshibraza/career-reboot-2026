package com.nova.core.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * [SpeechToText] backed by the platform recogniser.
 *
 * [SpeechRecognizer] is main-thread-only — every call and every callback has to happen on the
 * main looper — so the producer is pinned there with [flowOn]. Collectors can be on any
 * dispatcher.
 */
class AndroidSpeechToText(context: Context) : SpeechToText {

    private val appContext = context.applicationContext

    /**
     * One recogniser, reused for every session.
     *
     * Creating one per session cost a measured 660ms on the target phone before the microphone
     * went live, on top of ~390ms of the engine loading its language pack — so the first full
     * second of speech was recorded by nobody. For a wake word that is fatal: "Raza" is over in
     * about half that time, so the word was finished before anything was listening and the
     * engine reported NO_MATCH on the silence that followed.
     *
     * Only ever touched from the main looper, which [SpeechRecognizer] requires anyway, so no
     * further synchronisation is needed. Never destroyed: this lives as long as the process,
     * and the recogniser holds no microphone between sessions.
     */
    private var recognizer: SpeechRecognizer? = null

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun transcribe(languageTag: String): Flow<SpeechEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            trySend(SpeechEvent.Failed(SpeechError.UNAVAILABLE))
            close()
            return@callbackFlow
        }

        val recognizer = this@AndroidSpeechToText.recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(appContext)
                .also { this@AndroidSpeechToText.recognizer = it }

        // Distinguishes "the session ended on its own" from "the collector walked away".
        // Only the latter needs cancelling; cancelling a finished session logs
        // "not connected to the recognition service" and achieves nothing.
        var finished = false

        recognizer.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechEvent.Level(rmsdB))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstTranscript()?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstTranscript()

                // Every alternative, not just the winner. When a name is not recognised the
                // right one is often second or third, and without seeing the list there is no
                // way to tell a mishearing from a mis-scoring.
                val alternatives = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.take(MAX_LOGGED_ALTERNATIVES)
                    .orEmpty()
                Log.i(TAG, "heard: $alternatives")

                if (text.isNullOrBlank()) {
                    trySend(SpeechEvent.Failed(SpeechError.NO_MATCH))
                } else {
                    trySend(SpeechEvent.Final(text, alternatives.drop(1)))
                }
                finished = true
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Failed(error.toSpeechError()))
                finished = true
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Several, not one. A name the recogniser does not know is frequently not its top
            // guess, and the alternatives are what make a fuzzy wake word work at all.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_LOGGED_ALTERNATIVES)
            // Ask for on-device recognition where the device supports it; the platform falls
            // back to the network recogniser on its own when it doesn't.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        runCatching { recognizer.startListening(intent) }
            .onFailure {
                trySend(SpeechEvent.Failed(SpeechError.BUSY))
                finished = true
                close()
            }

        awaitClose {
            // Cancelled, never destroyed. Destroying is what forced the next session to rebuild
            // the engine from scratch, and cancel() already releases the microphone.
            runCatching { if (!finished) recognizer.cancel() }
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun Bundle?.firstTranscript(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun Int.toSpeechError(): SpeechError = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.TIMEOUT
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION_DENIED
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SpeechError.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.BUSY
        SpeechRecognizer.ERROR_CLIENT -> SpeechError.BUSY
        else -> SpeechError.UNKNOWN
    }

    private companion object {
        const val TAG = "NovaSpeech"
        const val MAX_LOGGED_ALTERNATIVES = 5
    }
}
