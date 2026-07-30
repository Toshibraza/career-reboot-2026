package com.nova.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nova.core.agent.routine.RoutineTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs a routine's command through the agent, outside any broadcast receiver.
 *
 * A receiver's process is only guaranteed to live while `onReceive` runs. The old design
 * launched multi-minute agent work on a static scope from there, which meant a routine that
 * fired while Nova had no UI on screen could be killed halfway through — after the tap that
 * opened an app, before the tap that finished the job. WorkManager owns a real execution
 * window: the enqueue survives process death, and the work runs in a process the system
 * keeps alive for it.
 *
 * The worker also carries the post-run bookkeeping (re-arming a daily routine, removing a
 * one-off) so that too cannot be lost with the receiver's process.
 */
class RoutineWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? NovaApplication ?: return Result.failure()
        val container = application.container

        return when (inputData.getString(KEY_ACTION)) {
            ACTION_RUN -> {
                val id = inputData.getString(KEY_ROUTINE_ID) ?: return Result.failure()
                runRoutine(container, id)
            }

            ACTION_REARM -> {
                val routines = container.routines.all()
                container.routineScheduler.scheduleAll(routines)
                Log.i(TAG, "re-armed ${routines.size} routines")
                Result.success()
            }

            ACTION_POWER_CONNECTED -> {
                // Plugging in ends the discharge, so battery routines become eligible again.
                container.routineStore.rearmBatteryRoutines()
                container.routineStore
                    .routinesFor(RoutineTrigger.PowerConnected)
                    .forEach { runRoutine(container, it.id) }
                Result.success()
            }

            ACTION_POWER_DISCONNECTED -> {
                container.routineStore
                    .routinesFor(RoutineTrigger.PowerDisconnected)
                    .forEach { runRoutine(container, it.id) }
                evaluateBattery(container)
                Result.success()
            }

            ACTION_BATTERY_LOW -> {
                evaluateBattery(container)
                Result.success()
            }

            else -> Result.failure()
        }
    }

    /**
     * Fires any armed battery routine whose threshold has been crossed, then disarms it.
     *
     * Without the latch a routine set at 20% would fire again at 19, 18 and 17 — which from
     * the outside is indistinguishable from a bug. The level is read at broadcast time by
     * [PowerReceiver] and carried here, so a delayed execution still judges the moment the
     * event actually happened.
     */
    private suspend fun evaluateBattery(container: NovaContainer) {
        val level = inputData.getInt(KEY_BATTERY_PERCENT, -1)
        if (level < 0) return

        container.routineStore.armedBatteryRoutines()
            .filter { (it.trigger as? RoutineTrigger.BatteryBelow)?.percent?.let { p -> level < p } == true }
            .forEach { routine ->
                container.routineStore.disarm(routine.id)
                runRoutine(container, routine.id)
            }
    }

    private suspend fun runRoutine(container: NovaContainer, id: String): Result {
        val routine = container.routines.all().firstOrNull { it.id == id }
        if (routine == null) {
            Log.i(TAG, "routine $id no longer exists, ignoring")
            return Result.success()
        }

        // The command runs as a child job registered with the container, so the orb's stop
        // control reaches a routine exactly as it reaches a spoken command. Stopping is a
        // deliberate user decision, so the run is not retried.
        try {
            coroutineScope {
                val command = launch {
                    val response = container.runtime.handle(routine.command)
                    Log.i(TAG, "\"${routine.command}\" -> ${response.spoken}")
                    container.speaker.speak(response.spoken)
                }
                container.activeCommands.track(command)
                command.join()
            }
        } catch (stopped: CancellationException) {
            Log.i(TAG, "routine ${routine.id} stopped by user")
            throw stopped
        }

        when (routine.trigger) {
            // Re-armed after firing rather than using a repeating alarm, so a routine
            // deleted at noon does not come back at eight tomorrow.
            is RoutineTrigger.Daily -> container.routineScheduler.schedule(routine)

            // A one-off is done. Removing it also stops it reappearing after a reboot.
            is RoutineTrigger.OnceAt -> container.routines.remove(routine.id)

            // Power and battery routines have no alarm to manage; PowerReceiver's latch
            // already disarmed a battery routine before enqueueing it here.
            is RoutineTrigger.BatteryBelow,
            RoutineTrigger.PowerConnected,
            RoutineTrigger.PowerDisconnected,
            -> Unit
        }

        return Result.success()
    }

    /**
     * Required because the work is expedited: on Android 11 and below, expedited work runs
     * inside a foreground service, and that service needs a notification.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.routine_channel_name),
                // Low: this notification is a legal requirement, not news.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.routine_notification_title))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "NovaRoutine"
        private const val CHANNEL_ID = "nova-routines"
        private const val NOTIFICATION_ID = 2

        private const val KEY_ACTION = "action"
        private const val KEY_ROUTINE_ID = "routineId"
        private const val KEY_BATTERY_PERCENT = "batteryPercent"
        private const val ACTION_RUN = "run"
        private const val ACTION_REARM = "rearm"
        private const val ACTION_POWER_CONNECTED = "powerConnected"
        private const val ACTION_POWER_DISCONNECTED = "powerDisconnected"
        private const val ACTION_BATTERY_LOW = "batteryLow"

        /** Runs the routine with [routineId] as soon as the system allows. */
        fun enqueueRun(context: Context, routineId: String) {
            val request = OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInputData(workDataOf(KEY_ACTION to ACTION_RUN, KEY_ROUTINE_ID to routineId))
                // Expedited: an alarm-driven routine should run at its time, not when the
                // system next feels like batching deferred work.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            // One queue per routine id: if the same routine somehow fires again before its
            // last run finished, the runs append rather than race each other.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("routine-run-$routineId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Re-schedules every stored routine's alarm, after boot or app update. */
        fun enqueueRearm(context: Context) {
            val request = OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInputData(workDataOf(KEY_ACTION to ACTION_REARM))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("routine-rearm", ExistingWorkPolicy.REPLACE, request)
        }

        /** Runs whatever routines a power or battery event calls for. */
        fun enqueuePowerEvent(context: Context, event: PowerEvent, batteryPercent: Int?) {
            val action = when (event) {
                PowerEvent.CONNECTED -> ACTION_POWER_CONNECTED
                PowerEvent.DISCONNECTED -> ACTION_POWER_DISCONNECTED
                PowerEvent.BATTERY_LOW -> ACTION_BATTERY_LOW
            }
            val request = OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ACTION to action,
                        KEY_BATTERY_PERCENT to (batteryPercent ?: -1),
                    ),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            // One queue for all power events: plugging in and straight back out must process
            // in order, or the re-arm from the plug-in could revive a routine the unplug
            // already fired.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("routine-power", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** A power-state change [PowerReceiver] noticed and handed to [RoutineWorker]. */
enum class PowerEvent { CONNECTED, DISCONNECTED, BATTERY_LOW }
