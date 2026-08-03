package com.nova.feature.localllm

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Finds the on-device model file and decides whether this phone can actually run it.
 *
 * The model is not bundled. A quantised Gemma is several hundred megabytes and its licence has
 * to be accepted by a person, so it is side-loaded rather than shipped, and its absence is a
 * normal state the app has to describe rather than crash on.
 */
class LocalModelStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Where a side-loaded model goes.
     *
     * The app's external files directory, so it can be pushed over adb without root:
     * ```
     * adb push gemma3-1b-it-int4.task \
     *   /sdcard/Android/data/com.nova.assistant/files/llm/model.task
     * ```
     */
    fun modelFile(): File = File(File(appContext.getExternalFilesDir(null), DIRECTORY), FILE_NAME)

    fun isInstalled(): Boolean = modelFile().let { it.isFile && it.length() > 0 }

    fun sizeBytes(): Long = modelFile().takeIf { it.isFile }?.length() ?: 0L

    /**
     * Free memory right now, which is what actually decides whether a load succeeds.
     *
     * Total RAM is the wrong number to look at: this device reports 5.7 GB total but routinely
     * has under 2 GB available, and the loader cares about the latter.
     */
    fun availableMemoryBytes(): Long {
        val manager = appContext.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem
    }

    /**
     * Whether loading is worth attempting.
     *
     * This used to demand one and a half times the file size, on the reasoning that weights have
     * to be resident. Two measurements from this project say file size does not predict runtime
     * footprint in either direction:
     *
     * - Qwen 1.5B is about 1.1 GB on disk and wanted roughly 2.3 GB to run — twice the rule.
     * - Gemma 3n E2B is 3.1 GB on disk and is built to run in about 2 GB, because its per-layer
     *   embeddings are streamed rather than held resident. The old rule demanded 4.7 GB and
     *   refused a model designed to fit.
     *
     * A single multiplier over file size cannot describe both, so it no longer tries. What
     * remains is a floor: below this there is no plausible working set for any model worth
     * running, and attempting it risks the memory pressure that gets Nova killed. Above it, the
     * loader is the only thing that actually knows, and it is allowed to answer.
     */
    fun status(): ModelStatus {
        if (!isInstalled()) return ModelStatus.NotInstalled

        val available = availableMemoryBytes()

        return if (available < MINIMUM_AVAILABLE_BYTES) {
            ModelStatus.TooLittleMemory(needed = MINIMUM_AVAILABLE_BYTES, available = available)
        } else {
            ModelStatus.Ready(sizeBytes())
        }
    }

    private companion object {
        const val DIRECTORY = "llm"
        const val FILE_NAME = "model.task"

        /**
         * Free memory below which no on-device model is worth attempting.
         *
         * Chosen from the smallest footprint any of these models has actually been observed to
         * need, not from the file on disk. It is a guard against a hopeless attempt, not a
         * prediction that a given model will fit.
         */
        const val MINIMUM_AVAILABLE_BYTES = 1_500L * 1024 * 1024
    }
}

sealed interface ModelStatus {

    data object NotInstalled : ModelStatus

    data class TooLittleMemory(val needed: Long, val available: Long) : ModelStatus

    data class Ready(val sizeBytes: Long) : ModelStatus
}
