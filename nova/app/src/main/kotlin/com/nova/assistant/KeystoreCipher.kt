package com.nova.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small strings with a key that never leaves the device's Keystore.
 *
 * Exists for the API keys. App-private SharedPreferences keeps other apps out, but the values
 * were readable in any device backup and by anything with root — and an OpenAI key in the
 * wrong hands is an open-ended bill. The Keystore key is hardware-backed where the device
 * supports it and non-exportable everywhere, so a copied preferences file decrypts nowhere.
 *
 * AES-GCM with a fresh random IV per encryption, IV stored alongside the ciphertext. Not
 * hand-rolled crypto — both the algorithm and the key storage are the platform's.
 *
 * Every operation that touches the Keystore can throw on a broken device (locked keystore,
 * corrupted key). Callers get null back instead, and treat it as "no stored value": losing a
 * pasted key to re-entry beats crashing the assistant at construction time.
 */
class KeystoreCipher(private val alias: String) {

    /** Base64 of IV + ciphertext, or null when the Keystore refuses. */
    fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }.getOrNull()

    /** The original string, or null for anything that does not decrypt cleanly. */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_LENGTH) { "too short to hold an IV" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH),
        )
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM's standard 12-byte IV; the Keystore cipher always produces this length. */
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
