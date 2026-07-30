package com.nova.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.nova.core.agent.routine.Routine
import com.nova.core.agent.routine.RoutineTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs routines triggered by power and battery state.
 *
 * These have no moment in the future to wake up for, only a state change to notice, so they
 * ride on system broadcasts rather than the alarm clock.
 *
 * The honest limitation: `ACTION_BATTERY_LOW` fires at roughly 15%, and `ACTION_BATTERY_CHANGED`
 * cannot be registered in a manifest. So a threshold above ~15% is evaluated when the charger
 * is unplugged, when the battery gets low, and whenever Nova is running — not continuously. A
 * routine set at 40% may therefore fire late. Continuous monitoring would need a foreground
 * service running all day, which is a worse trade than a late battery-saver toggle.
 */
class PowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? NovaApplication ?: return
        val container = application.container

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> scope.launch {
                // Plugging in ends the discharge, so battery routines become eligible again.
                container.routineStore.rearmBatteryRoutines()
                container.routineStore
                    .routinesFor(RoutineTrigger.PowerConnected)
                    .forEach { run(container, it) }
            }

            Intent.ACTION_POWER_DISCONNECTED -> scope.launch {
                container.routineStore
                    .routinesFor(RoutineTrigger.PowerDisconnected)
                    .forEach { run(container, it) }
                evaluateBattery(context, container)
            }

            Intent.ACTION_BATTERY_LOW -> scope.launch { evaluateBattery(context, container) }
        }
    }

    /**
     * Fires any armed battery routine whose threshold has been crossed, then disarms it.
     *
     * Without the latch a routine set at 20% would fire again at 19, 18 and 17 — which from
     * the outside is indistinguishable from a bug.
     */
    private suspend fun evaluateBattery(context: Context, container: NovaContainer) {
        val level = batteryPercent(context) ?: return

        container.routineStore.armedBatteryRoutines()
            .filter { (it.trigger as? RoutineTrigger.BatteryBelow)?.percent?.let { p -> level < p } == true }
            .forEach { routine ->
                container.routineStore.disarm(routine.id)
                run(container, routine)
            }
    }

    private fun batteryPercent(context: Context): Int? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        return level * 100 / scale
    }

    private fun run(container: NovaContainer, routine: Routine) {
        val job = scope.launch {
            val response = container.runtime.handle(routine.command)
            Log.i(TAG, "\"${routine.command}\" -> ${response.spoken}")
            container.speaker.speak(response.spoken)
        }
        // Same stop control as every other entry point.
        container.activeCommands.track(job)
    }

    private companion object {
        const val TAG = "NovaRoutine"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
