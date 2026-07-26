package com.nova.feature.comms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.nova.core.agent.comms.Contact
import com.nova.core.agent.match.FuzzyMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds a person in the address book by the name that was spoken.
 *
 * Uses the same [FuzzyMatcher] as apps and memory, for the same reason: the input is a speech
 * transcript. But the stakes here are different — resolving the wrong app wastes a tap, while
 * resolving the wrong person makes a phone ring. So a match is never acted on directly; the
 * caller reads the resolved name and number back and waits to be told to proceed.
 */
class ContactDirectory(context: Context) {

    private val appContext = context.applicationContext

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Best match for [query], or null when nothing in the address book is close enough. */
    suspend fun resolve(query: String): Contact? = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext null
        FuzzyMatcher.best(query, load()) { it.name }
    }

    private fun load(): List<Contact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        return appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val number = cursor.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty()) add(Contact(name, number))
                }
            }
                // One person often has several numbers; the first is the one to offer, and
                // offering the same name three times would make the read-back useless.
                .distinctBy { it.name }
        }.orEmpty()
    }
}
