package com.nova.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nova.core.agent.routine.RoutineTrigger
import com.nova.feature.routines.RoutineScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs a routine when its alarm fires, and re-arms it if it repeats.
 *
 * Lives in the app module rather than `:feature:routines` because firing a routine means
 * running its command through the full agent, and the agent is assembled here.
 */
class RoutineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? NovaApplication ?: return
        val container = application.container

        when (intent.action) {
            RoutineScheduler.ACTION_RUN_ROUTINE -> {
                val id = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_ID) ?: return
                run(container, id)
            }

            // Alarms do not survive a reboot, so everything has to be put back.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> scope.launch {
                val routines = container.routines.all()
                container.routineScheduler.scheduleAll(routines)
                Log.i(TAG, "re-armed ${routines.size} routines after ${intent.action}")
            }
        }
    }

    private fun run(container: NovaContainer, id: String) {
        val job = scope.launch {
            val routine = container.routines.all().firstOrNull { it.id == id }
            if (routine == null) {
                Log.i(TAG, "routine $id no longer exists, ignoring")
                return@launch
            }

            val response = container.runtime.handle(routine.command)
            Log.i(TAG, "\"${routine.command}\" -> ${response.spoken}")
            container.speaker.speak(response.spoken)

            when (routine.trigger) {
                // Re-armed after firing rather than using a repeating alarm, so a routine
                // deleted at noon does not come back at eight tomorrow.
                is RoutineTrigger.Daily -> container.routineScheduler.schedule(routine)

                // A one-off is done. Removing it also stops it reappearing after a reboot.
                is RoutineTrigger.OnceAt -> container.routines.remove(routine.id)

                // Power and battery routines never reach here — they are driven by
                // PowerReceiver, and nothing about them needs re-arming on a clock.
                is RoutineTrigger.BatteryBelow,
                RoutineTrigger.PowerConnected,
                RoutineTrigger.PowerDisconnected,
                -> Unit
            }
        }
        // A routine acting on the phone must answer to the same stop control as everything
        // else — firing unattended is exactly when a stop button matters most.
        container.activeCommands.track(job)
    }

    private companion object {
        const val TAG = "NovaRoutine"

        /** Outlives the broadcast: a routine may open an app or speak a sentence. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
