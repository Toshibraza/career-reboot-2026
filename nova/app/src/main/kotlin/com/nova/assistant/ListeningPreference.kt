package com.nova.assistant

import android.content.Context

/**
 * Whether the user wants Raza listening for its name.
 *
 * The toggle used to read [NovaListeningService.isRunning], which is process state: it says
 * whether the service is up right now, not whether anyone asked for it. So every reinstall, and
 * every time Android reclaimed the process, silently turned always-listening off — and the
 * switch showed off too, which is honest but not what was wanted.
 *
 * Kept separate from the service so the answer survives the process that acts on it.
 */
class ListeningPreference(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun wanted(): Boolean = preferences.getBoolean(KEY, false)

    fun setWanted(wanted: Boolean) {
        preferences.edit().putBoolean(KEY, wanted).apply()
    }

    private companion object {
        const val FILE = "nova-listening"
        const val KEY = "always_listening"
    }
}
