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
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override suspend fun add(routine: Routine) = withContext(Dispatchers.IO) {
        val (kind, time) = when (val trigger = routine.trigger) {
            is RoutineTrigger.Daily -> DAILY to trigger.at
            is RoutineTrigger.OnceAt -> ONCE to trigger.at
        }

        val values = ContentValues().apply {
            put("id", routine.id)
            put("kind", kind)
            put("hour", time.hour)
            put("minute", time.minute)
            put("command", routine.command)
            put("created_at", routine.createdAt)
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

    private fun readAll(): List<Routine> =
        helper.readableDatabase.query(
            TABLE,
            arrayOf("id", "kind", "hour", "minute", "command", "created_at"),
            null,
            null,
            null,
            null,
            "created_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val at = TimeOfDay(cursor.getInt(2), cursor.getInt(3))
                    add(
                        Routine(
                            id = cursor.getString(0),
                            trigger = if (cursor.getString(1) == ONCE) {
                                RoutineTrigger.OnceAt(at)
                            } else {
                                RoutineTrigger.Daily(at)
                            },
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
        const val VERSION = 1
        const val DAILY = "daily"
        const val ONCE = "once"
    }
}
