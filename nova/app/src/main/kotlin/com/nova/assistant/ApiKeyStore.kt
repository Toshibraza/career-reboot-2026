package com.nova.assistant

import android.content.Context

/**
 * Where the OpenAI key comes from.
 *
 * Two sources, in order: one the user pasted into the app, then the build-time value from
 * `local.properties`. The stored one wins, so rotating a key is a paste rather than a rebuild
 * and reinstall — which matters, because the build-time key is baked into the APK and the only
 * safe response to a leaked key is to replace it quickly.
 *
 * Stored in app-private preferences. That keeps it away from other apps, but it is not
 * encrypted at rest: a rooted device or a device backup could reach it. Good enough for a
 * personal build; a published app should not hold a provider key on the device at all, and
 * should call a backend that holds it instead.
 */
class ApiKeyStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The key to use right now, or empty when none is configured. */
    fun current(): String =
        preferences.getString(KEY, null)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: BuildConfig.OPENAI_API_KEY.trim()

    fun hasKey(): Boolean = current().isNotEmpty()

    /** True when the key in use was pasted in the app rather than baked into the build. */
    fun isUserProvided(): Boolean =
        !preferences.getString(KEY, null)?.trim().isNullOrEmpty()

    fun save(key: String) {
        val cleaned = key.trim()
        preferences.edit().apply {
            if (cleaned.isEmpty()) remove(KEY) else putString(KEY, cleaned)
        }.apply()
    }

    /** Forgets the pasted key, falling back to the build-time one if there is one. */
    fun clear() = preferences.edit().remove(KEY).apply()

    /**
     * A form safe to show on screen — enough to tell two keys apart, not enough to use.
     */
    fun masked(): String {
        val key = current()
        return when {
            key.isEmpty() -> "not set"
            key.length <= MASK_VISIBLE * 2 -> "•".repeat(key.length)
            else -> key.take(MASK_VISIBLE) + "…" + key.takeLast(MASK_VISIBLE)
        }
    }

    private companion object {
        const val FILE = "nova-credentials"
        const val KEY = "openai_api_key"
        const val MASK_VISIBLE = 6
    }
}
