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
     * Weights have to be resident, plus room for the KV cache and the runtime, so the rule of
     * thumb is roughly one and a half times the file size. Attempting a load that cannot fit
     * does not fail cleanly — it drives the device into memory pressure and gets Nova killed.
     */
    fun status(): ModelStatus {
        if (!isInstalled()) return ModelStatus.NotInstalled

        val needed = (sizeBytes() * MEMORY_HEADROOM).toLong()
        val available = availableMemoryBytes()

        return if (available < needed) {
            ModelStatus.TooLittleMemory(needed = needed, available = available)
        } else {
            ModelStatus.Ready(sizeBytes())
        }
    }

    private companion object {
        const val DIRECTORY = "llm"
        const val FILE_NAME = "model.task"
        const val MEMORY_HEADROOM = 1.5
    }
}

sealed interface ModelStatus {

    data object NotInstalled : ModelStatus

    data class TooLittleMemory(val needed: Long, val available: Long) : ModelStatus

    data class Ready(val sizeBytes: Long) : ModelStatus
}
