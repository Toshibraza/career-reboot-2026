package com.nova.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nova.feature.routines.RoutineScheduler

/**
 * Hands a fired routine to [RoutineWorker], and re-arms alarms after a reboot or update.
 *
 * The receiver itself does no agent work: a receiver's process is only guaranteed to live
 * while `onReceive` runs, and a routine can take minutes of tapping and typing. Everything
 * long-lived goes through WorkManager, whose enqueue survives process death.
 *
 * Lives in the app module rather than `:feature:routines` because firing a routine means
 * running its command through the full agent, and the agent is assembled here.
 */
class RoutineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RoutineScheduler.ACTION_RUN_ROUTINE -> {
                val id = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_ID) ?: return
                RoutineWorker.enqueueRun(context, id)
            }

            // Alarms do not survive a reboot, so everything has to be put back.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> RoutineWorker.enqueueRearm(context)
        }
    }
}
