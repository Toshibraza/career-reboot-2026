package com.nova.feature.localllm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.nova.core.llm.ChatClient
import com.nova.core.llm.ModelUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * A [ChatClient] running a quantised model on the phone, with no network.
 *
 * The trade against the cloud client is speed, not capability-in-principle. On a mid-range
 * chipset with no usable NPU this is CPU inference, and a planning step costs seconds rather
 * than milliseconds — so it is worth it for privacy and for working with no signal, and it is
 * not worth it for a fast eight-step task. Both implement the same interface, and
 * `NovaContainer` decides which is in play.
 */
class LocalChatClient(
    context: Context,
    private val store: LocalModelStore,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : ChatClient {

    private val appContext = context.applicationContext

    /**
     * One inference at a time.
     *
     * The engine is not reentrant, and two concurrent generations on a device this size would
     * mean two copies of the working set — which is how the app gets killed rather than slowed.
     */
    private val lock = Mutex()

    @Volatile
    private var engine: LlmInference? = null

    override suspend fun complete(system: String, user: String): String =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val inference = engine ?: load()
                val prompt = "$system\n\n$user\n\nJSON:"

                // Whether the planner could see the screen decides whether a repeated action
                // is the model ignoring feedback or the model never receiving any. Logs the
                // fact, not the screen contents.
                val sawScreen = "(cannot read the screen)" !in user
                Log.i(TAG, "prompt ${prompt.length} chars, screen visible: $sawScreen")

                var reply: String
                val elapsed = measureTimeMillis {
                    reply = runCatching { inference.generateResponse(prompt) }
                        .getOrElse { failure ->
                            // A generation failure usually means memory, and a dead engine
                            // must not be reused.
                            release()
                            throw ModelUnavailableException(
                                "The on-device model couldn't answer. ${failure.message.orEmpty()}".trim(),
                            )
                        }
                }

                // Throughput is the whole question with on-device inference, and guessing at
                // it from feel is how people ship something unusable. The reply itself is
                // logged too: a small model's failures are almost always in what it wrote,
                // and without seeing that, a bad answer is indistinguishable from a bug.
                Log.i(TAG, "generated ${reply.length} chars in ${elapsed}ms")
                Log.i(TAG, "reply: ${reply.replace('\n', ' ').take(MAX_LOGGED_REPLY)}")
                reply
            }
        }

    private fun load(): LlmInference {
        when (val status = store.status()) {
            is ModelStatus.NotInstalled -> throw ModelUnavailableException(
                "No on-device model is installed.",
            )

            is ModelStatus.TooLittleMemory -> throw ModelUnavailableException(
                "This phone doesn't have enough free memory to load the model right now.",
            )

            is ModelStatus.Ready -> Unit.also {
                Log.i(TAG, "loading model, ${status.sizeBytes / 1_048_576} MB")
            }
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(store.modelFile().absolutePath)
            .setMaxTokens(maxTokens)
            .build()

        val created = runCatching { LlmInference.createFromOptions(appContext, options) }
            .getOrElse { failure ->
                throw ModelUnavailableException(
                    "The on-device model failed to load. ${failure.message.orEmpty()}".trim(),
                )
            }

        engine = created
        return created
    }

    /** Frees the model. Worth calling when the planner is idle on a memory-tight device. */
    fun release() {
        runCatching { engine?.close() }
        engine = null
    }

    private companion object {
        const val TAG = "NovaLocalLlm"

        /**
         * Covers the system prompt, a screen listing and a short JSON reply.
         *
         * Larger windows cost memory proportionally, and the planner only ever needs one small
         * object back.
         */
        const val DEFAULT_MAX_TOKENS = 1024

        /** Enough to see the shape of a bad reply without flooding the log. */
        const val MAX_LOGGED_REPLY = 600
    }
}
