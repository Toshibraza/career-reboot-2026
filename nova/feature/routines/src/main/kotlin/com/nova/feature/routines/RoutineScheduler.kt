package com.nova.feature.routines

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nova.core.agent.routine.Routine
import com.nova.core.agent.routine.RoutineTrigger
import com.nova.core.agent.routine.TimeOfDay
import java.util.Calendar

/**
 * Puts routines on the system alarm clock.
 *
 * Uses `setAndAllowWhileIdle` rather than an exact alarm. Exact alarms need
 * `SCHEDULE_EXACT_ALARM`, which since Android 12 is a permission the user must grant on a
 * settings screen, and asking for it to play music at 8 am would be disproportionate. The
 * trade is that firing can drift by a few minutes under Doze — acceptable for "every morning",
 * and worth knowing before relying on it for a medication reminder.
 *
 * Daily routines are rescheduled after each firing rather than using a repeating alarm, so a
 * routine deleted at noon does not fire again at eight the next morning.
 */
class RoutineScheduler(
    context: Context,
    /** The receiver woken when a routine is due. Lives in the app module, which owns the runtime. */
    private val receiver: Class<*>,
) {

    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(routine: Routine) {
        val at = when (val trigger = routine.trigger) {
            is RoutineTrigger.Daily -> trigger.at
            is RoutineTrigger.OnceAt -> trigger.at
        }

        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextOccurrenceOf(at),
            pendingIntent(routine.id),
        )
    }

    fun cancel(routineId: String) {
        alarms.cancel(pendingIntent(routineId))
    }

    /** Re-arms everything. Called after a reboot, which clears the alarm table. */
    fun scheduleAll(routines: List<Routine>) = routines.forEach(::schedule)

    /**
     * The next time [at] comes round.
     *
     * Strictly in the future: scheduling "8 am" at 8:00:30 must mean tomorrow, not a moment
     * that has already passed and would fire instantly.
     */
    fun nextOccurrenceOf(at: TimeOfDay, now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, at.hour)
            set(Calendar.MINUTE, at.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    private fun pendingIntent(routineId: String): PendingIntent {
        val intent = Intent(appContext, receiver).apply {
            action = ACTION_RUN_ROUTINE
            putExtra(EXTRA_ROUTINE_ID, routineId)
            // In the data, not just an extra: PendingIntent equality ignores extras, so two
            // routines would otherwise share one alarm slot and the second would replace the
            // first.
            data = android.net.Uri.parse("nova://routine/$routineId")
        }

        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_RUN_ROUTINE = "com.nova.assistant.RUN_ROUTINE"
        const val EXTRA_ROUTINE_ID = "routine_id"
    }
}
