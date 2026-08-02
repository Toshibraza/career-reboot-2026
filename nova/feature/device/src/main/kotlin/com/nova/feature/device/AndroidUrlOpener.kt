package com.nova.feature.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nova.core.agent.web.UrlOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a link with whichever app claims it.
 *
 * Deliberately not "open the browser". A YouTube link handed to Android is claimed by the
 * YouTube app when it is installed, which is what makes "play Coke Studio" land in the app
 * rather than in a web page — and falls back to a browser by itself when it is not.
 */
class AndroidUrlOpener(context: Context) : UrlOpener {

    private val appContext = context.applicationContext

    override suspend fun open(url: String): Boolean = withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // Started from a service or a background scope as often as from an activity.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            appContext.startActivity(intent)
            true
        } catch (notFound: ActivityNotFoundException) {
            // A phone with no browser and no app for this scheme. Rare, but the caller has a
            // better answer for the user than a crash.
            false
        }
    }
}
