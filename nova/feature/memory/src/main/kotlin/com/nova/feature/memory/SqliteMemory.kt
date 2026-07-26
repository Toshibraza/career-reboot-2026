package com.nova.feature.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nova.core.agent.match.FuzzyMatcher
import com.nova.core.agent.memory.Memory
import com.nova.core.agent.memory.MemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Memory] on top of SQLite.
 *
 * Plain `SQLiteOpenHelper` rather than Room: this is one table with four columns, and Room's
 * annotation processing would cost more build time than the code it replaces.
 *
 * Recall reuses [FuzzyMatcher] — the same scoring that resolves "whats app" to an installed app
 * resolves "parking spot" to "my parking spot". Asking about something is the same shape of
 * problem as naming an app, and it should not have a second, subtly different implementation.
 */
class SqliteMemory(context: Context) : Memory {

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext,
        DATABASE,
        null,
        VERSION,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    subject TEXT PRIMARY KEY NOT NULL,
                    detail TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Nothing to migrate yet. When there is, migrate rather than drop — this table is
            // the one part of Nova holding things the user cannot get back.
        }
    }

    override suspend fun remember(subject: String, detail: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("subject", subject.trim())
            put("detail", detail.trim())
            put("updated_at", System.currentTimeMillis())
        }
        // Replace on conflict: being told a new parking spot means the old one is wrong, not
        // that there are now two.
        helper.writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    override suspend fun recall(query: String): MemoryEntry? = withContext(Dispatchers.IO) {
        FuzzyMatcher.best(query, readAll()) { it.subject }
    }

    override suspend fun all(): List<MemoryEntry> = withContext(Dispatchers.IO) { readAll() }

    override suspend fun forget(query: String): MemoryEntry? = withContext(Dispatchers.IO) {
        val match = FuzzyMatcher.best(query, readAll()) { it.subject } ?: return@withContext null
        helper.writableDatabase.delete(TABLE, "subject = ?", arrayOf(match.subject))
        match
    }

    private fun readAll(): List<MemoryEntry> =
        helper.readableDatabase.query(
            TABLE,
            arrayOf("subject", "detail", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MemoryEntry(
                            subject = cursor.getString(0),
                            detail = cursor.getString(1),
                            updatedAt = cursor.getLong(2),
                        ),
                    )
                }
            }
        }

    private companion object {
        const val DATABASE = "nova-memory.db"
        const val TABLE = "memories"
        const val VERSION = 1
    }
}
