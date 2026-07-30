package com.nova.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Notices power and battery state changes and hands them to [RoutineWorker].
 *
 * These have no moment in the future to wake up for, only a state change to notice, so they
 * ride on system broadcasts rather than the alarm clock. The receiver only records what
 * happened — which event, at what battery level — and enqueues; running routines is minutes
 * of agent work, which cannot live in a receiver's process. The level is read here, at the
 * moment of the event, so a delayed worker still judges the state that triggered it.
 *
 * The honest limitation: `ACTION_BATTERY_LOW` fires at roughly 15%, and `ACTION_BATTERY_CHANGED`
 * cannot be registered in a manifest. So a threshold above ~15% is evaluated when the charger
 * is unplugged, when the battery gets low, and whenever Nova is running — not continuously. A
 * routine set at 40% may therefore fire late. Continuous monitoring would need a foreground
 * service running all day, which is a worse trade than a late battery-saver toggle.
 */
class PowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> PowerEvent.CONNECTED
            Intent.ACTION_POWER_DISCONNECTED -> PowerEvent.DISCONNECTED
            Intent.ACTION_BATTERY_LOW -> PowerEvent.BATTERY_LOW
            else -> return
        }
        RoutineWorker.enqueuePowerEvent(context, event, batteryPercent(context))
    }

    private fun batteryPercent(context: Context): Int? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        return level * 100 / scale
    }
}
