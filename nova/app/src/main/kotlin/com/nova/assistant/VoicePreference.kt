package com.nova.assistant

import android.content.Context

/**
 * The voice the user picked, if any.
 *
 * Exists because gender cannot be read from Android's TTS API. The engine's identifiers are
 * opaque — `en-in-x-enc`, `en-us-x-iom` — so any automatic choice is a guess, and a guess that
 * has already been wrong twice. Someone who has heard the voices knows better than the
 * heuristic, and their choice should outlive the app being closed.
 */
class VoicePreference(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Null means automatic. */
    fun current(): String? = preferences.getString(KEY, null)

    fun save(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY) else putString(KEY, id)
        }.apply()
    }

    private companion object {
        const val FILE = "nova-voice"
        const val KEY = "voice_id"
    }
}
