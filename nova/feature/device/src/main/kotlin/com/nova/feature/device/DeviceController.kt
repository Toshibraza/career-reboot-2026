package com.nova.feature.device

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import com.nova.core.agent.LevelChange
import com.nova.core.agent.VolumeStream
import kotlin.math.roundToInt

/**
 * The thin layer that actually talks to Android system services.
 *
 * Kept separate from [DeviceActionExecutor] so the mapping from intents to results stays
 * readable, and so these calls can be exercised in isolation on a device.
 * Everything here throws on failure; the executor turns throwables into spoken answers.
 */
class DeviceController(context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    // --- Torch ---------------------------------------------------------------------------

    /** True when at least one camera on the device has a flash unit. */
    fun hasTorch(): Boolean = torchCameraId() != null

    fun setTorch(on: Boolean) {
        val id = torchCameraId() ?: error("This device has no flash.")
        cameraManager.setTorchMode(id, on)
    }

    private fun torchCameraId(): String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    // --- Volume --------------------------------------------------------------------------

    /** Applies [level] to [stream] and returns the resulting percentage. */
    fun setVolume(stream: VolumeStream, level: LevelChange): Int {
        val androidStream = stream.toAndroidStream()
        val max = audioManager.getStreamMaxVolume(androidStream)
        if (max <= 0) error("That audio stream isn't available.")

        val currentPercent = audioManager.getStreamVolume(androidStream) * 100 / max
        val target = level.resolve(currentPercent)
        val index = (target / 100f * max).roundToInt().coerceIn(0, max)

        audioManager.setStreamVolume(androidStream, index, AudioManager.FLAG_SHOW_UI)
        return index * 100 / max
    }

    private fun VolumeStream.toAndroidStream(): Int = when (this) {
        VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        VolumeStream.CALL -> AudioManager.STREAM_VOICE_CALL
    }

    // --- Brightness ----------------------------------------------------------------------

    /** WRITE_SETTINGS is a special permission: a settings screen, not a runtime dialog. */
    fun canWriteSettings(): Boolean = Settings.System.canWrite(appContext)

    /** Applies [level] to screen brightness and returns the resulting percentage. */
    fun setBrightness(level: LevelChange): Int {
        val resolver = appContext.contentResolver
        val current = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            DEFAULT_BRIGHTNESS,
        )
        val currentPercent = current * 100 / MAX_BRIGHTNESS
        val target = level.resolve(currentPercent)

        // Auto-brightness would immediately overwrite whatever we set, so turn it off first.
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        // Floor at 1: a value of 0 leaves the screen unreadable and looks like a crash.
        val raw = (target / 100f * MAX_BRIGHTNESS).roundToInt().coerceIn(1, MAX_BRIGHTNESS)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, raw)

        return raw * 100 / MAX_BRIGHTNESS
    }

    // --- Apps ----------------------------------------------------------------------------

    /**
     * Launches [packageName].
     *
     * Note for Phase 2: Android 10+ blocks activity starts from the background, so calling this
     * from the wake-word service while Nova is not visible needs either SYSTEM_ALERT_WINDOW or a
     * full-screen-intent notification. From the foreground UI it works as-is.
     */
    fun launch(packageName: String) {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("That app has no launcher screen.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun LevelChange.resolve(currentPercent: Int): Int = when (this) {
        is LevelChange.Absolute -> percent
        is LevelChange.Relative -> currentPercent + deltaPercent
        LevelChange.Min -> 0
        LevelChange.Max -> 100
    }.coerceIn(0, 100)

    private companion object {
        const val MAX_BRIGHTNESS = 255
        const val DEFAULT_BRIGHTNESS = 128
    }
}
