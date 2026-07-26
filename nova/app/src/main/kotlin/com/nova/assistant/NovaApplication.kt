package com.nova.assistant

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class NovaApplication : Application() {

    /** Shared by the UI and the listening service so both talk to the same agent. */
    val container: NovaContainer by lazy { NovaContainer(this) }

    override fun onCreate() {
        super.onCreate()
        registerPowerReceiver()
    }

    /**
     * Power broadcasts have to be registered at runtime, not in the manifest.
     *
     * Since Android 8 the system refuses to deliver these to manifest-declared receivers in
     * background apps — confirmed on device, where logcat showed "Background execution not
     * allowed: receiving Intent ACTION_POWER_DISCONNECTED" for every app that tried. A
     * runtime registration from the application object is delivered normally.
     *
     * The trade this makes explicit: power routines work while Nova's process is alive. In
     * practice it usually is, because the accessibility service keeps it bound — but if
     * Android kills the process, a charger event is missed rather than queued. The
     * alternative, a foreground service running all day purely to watch the charger, costs
     * more battery than the routines save.
     */
    private fun registerPowerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }

        ContextCompat.registerReceiver(
            this,
            PowerReceiver(),
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
