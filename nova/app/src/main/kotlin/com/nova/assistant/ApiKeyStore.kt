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
 * Stored encrypted with a Keystore-held key. App-private preferences already keep other apps
 * out, but plaintext values travel in device backups and are readable with root; the Keystore
 * key travels nowhere, so neither route yields a usable key. Values that fail to decrypt are
 * treated as absent — losing a pasted key to re-entry beats crashing.
 *
 * A pre-existing plaintext value (from a build before encryption) is migrated on first read.
 * The build-time key still ends up in the APK; a published app should call a backend instead.
 */
class ApiKeyStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher(KEY_ALIAS)

    /** The key to use right now, or empty when none is configured. */
    fun current(): String =
        read(KEY).takeUnless { it.isNullOrEmpty() }
            ?: BuildConfig.OPENAI_API_KEY.trim()

    /**
     * The Apify token used for web search, or null.
     *
     * Kept in the same private file for the same reasons, and separate from the OpenAI key
     * because they are independent: search works without a planner and vice versa.
     */
    fun apifyToken(): String? =
        read(APIFY_KEY)?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.APIFY_TOKEN.trim().takeIf { it.isNotEmpty() }

    fun saveApifyToken(token: String) = write(APIFY_KEY, token)

    fun hasKey(): Boolean = current().isNotEmpty()

    /** True when the key in use was pasted in the app rather than baked into the build. */
    fun isUserProvided(): Boolean = !read(KEY).isNullOrEmpty()

    fun save(key: String) = write(KEY, key)

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

    /**
     * Reads a value, decrypting it — or migrating it if it predates encryption.
     *
     * Distinguishing the two by decrypting first: a legitimate ciphertext never survives as
     * one by accident, so a failed decrypt of a non-empty value means it is an old plaintext
     * entry, which is re-saved encrypted and never written back in the clear.
     */
    private fun read(name: String): String? {
        val stored = preferences.getString(name, null)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null

        cipher.decrypt(stored)?.let { return it }

        // Plaintext from a pre-encryption build. Use it, and upgrade it in place.
        write(name, stored)
        return stored
    }

    private fun write(name: String, value: String) {
        val cleaned = value.trim()
        preferences.edit().apply {
            if (cleaned.isEmpty()) {
                remove(name)
            } else {
                // Falls back to plaintext only if the Keystore itself is broken; a working
                // assistant with yesterday's storage beats one that cannot hold a key at all.
                putString(name, cipher.encrypt(cleaned) ?: cleaned)
            }
        }.apply()
    }

    private companion object {
        const val FILE = "nova-credentials"
        const val KEY = "openai_api_key"
        const val APIFY_KEY = "apify_token"
        const val KEY_ALIAS = "nova-credentials-key"
        const val MASK_VISIBLE = 6
    }
}
