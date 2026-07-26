package com.nova.feature.routines

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nova.core.agent.match.FuzzyMatcher
import com.nova.core.agent.routine.Routine
import com.nova.core.agent.routine.RoutineStore
import com.nova.core.agent.routine.RoutineTrigger
import com.nova.core.agent.routine.TimeOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Routines on disk, so they survive a reboot.
 *
 * The trigger is stored as three flat columns rather than serialised, because a schedule the
 * user set is the kind of thing that must still be readable after a schema change — and the
 * whole point of a routine is that it is still there tomorrow.
 */
class SqliteRoutineStore(context: Context) : RoutineStore {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DATABASE, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY NOT NULL,
                    kind TEXT NOT NULL,
                    hour INTEGER NOT NULL,
                    minute INTEGER NOT NULL,
                    command TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    threshold INTEGER NOT NULL DEFAULT 0,
                    armed INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Migrated, not dropped. There are already routines on real devices, and losing
            // someone's 8 am alarm to a schema change is the kind of thing that stops them
            // trusting the feature.
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN threshold INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN armed INTEGER NOT NULL DEFAULT 1")
            }
        }
    }

    /** Re-arms every battery routine. Called when a charger is connected. */
    suspend fun rearmBatteryRoutines() = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE SET armed = 1 WHERE kind = '$BATTERY'",
        )
        Unit
    }

    /** Marks a routine as spent so it does not fire again until re-armed. */
    suspend fun disarm(id: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("armed", 0) }
        helper.writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
        Unit
    }

    /** Battery routines that are still armed. */
    suspend fun armedBatteryRoutines(): List<Routine> = withContext(Dispatchers.IO) {
        readAll(where = "kind = '$BATTERY' AND armed = 1")
    }

    suspend fun routinesFor(trigger: RoutineTrigger): List<Routine> = withContext(Dispatchers.IO) {
        val kind = when (trigger) {
            is RoutineTrigger.PowerConnected -> POWER_ON
            is RoutineTrigger.PowerDisconnected -> POWER_OFF
            else -> return@withContext emptyList()
        }
        readAll(where = "kind = '$kind'")
    }

    override suspend fun add(routine: Routine) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", routine.id)
            put("command", routine.command)
            put("created_at", routine.createdAt)
            put("armed", 1)
            put("hour", 0)
            put("minute", 0)
            put("threshold", 0)

            when (val trigger = routine.trigger) {
                is RoutineTrigger.Daily -> {
                    put("kind", DAILY)
                    put("hour", trigger.at.hour)
                    put("minute", trigger.at.minute)
                }

                is RoutineTrigger.OnceAt -> {
                    put("kind", ONCE)
                    put("hour", trigger.at.hour)
                    put("minute", trigger.at.minute)
                }

                is RoutineTrigger.BatteryBelow -> {
                    put("kind", BATTERY)
                    put("threshold", trigger.percent)
                }

                RoutineTrigger.PowerConnected -> put("kind", POWER_ON)
                RoutineTrigger.PowerDisconnected -> put("kind", POWER_OFF)
            }
        }
        helper.writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override suspend fun all(): List<Routine> = withContext(Dispatchers.IO) { readAll() }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete(TABLE, "id = ?", arrayOf(id))
        Unit
    }

    override suspend fun removeMatching(query: String): Routine? = withContext(Dispatchers.IO) {
        val routines = readAll()
        // Matched on the command, because that is how a user refers to one: they say "cancel
        // the reminder to buy milk", never "cancel routine 7".
        val match = FuzzyMatcher.best(query, routines) { it.command.removePrefix("say ") }
            ?: return@withContext null

        helper.writableDatabase.delete(TABLE, "id = ?", arrayOf(match.id))
        match
    }

    private fun readAll(where: String? = null): List<Routine> =
        helper.readableDatabase.query(
            TABLE,
            arrayOf("id", "kind", "hour", "minute", "command", "created_at", "threshold"),
            where,
            null,
            null,
            null,
            "created_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val at = TimeOfDay(cursor.getInt(2), cursor.getInt(3))
                    val trigger = when (cursor.getString(1)) {
                        ONCE -> RoutineTrigger.OnceAt(at)
                        BATTERY -> RoutineTrigger.BatteryBelow(cursor.getInt(6))
                        POWER_ON -> RoutineTrigger.PowerConnected
                        POWER_OFF -> RoutineTrigger.PowerDisconnected
                        else -> RoutineTrigger.Daily(at)
                    }

                    add(
                        Routine(
                            id = cursor.getString(0),
                            trigger = trigger,
                            command = cursor.getString(4),
                            createdAt = cursor.getLong(5),
                        ),
                    )
                }
            }
        }

    private companion object {
        const val DATABASE = "nova-routines.db"
        const val TABLE = "routines"

        /** 2 added `threshold` and `armed` for battery and charger triggers. */
        const val VERSION = 2

        const val DAILY = "daily"
        const val ONCE = "once"
        const val BATTERY = "battery_below"
        const val POWER_ON = "power_connected"
        const val POWER_OFF = "power_disconnected"
    }
}
