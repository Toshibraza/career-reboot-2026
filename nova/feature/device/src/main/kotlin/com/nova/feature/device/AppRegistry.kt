package com.nova.feature.device

import android.content.Context
import android.content.Intent
import com.nova.core.agent.match.FuzzyMatcher

data class InstalledApp(val label: String, val packageName: String)

/** Resolves what the user said into a package to launch. Scoring lives in [FuzzyMatcher]. */
class AppRegistry(context: Context) {

    private val packageManager = context.applicationContext.packageManager

    @Volatile
    private var cache: List<InstalledApp>? = null

    /**
     * Every app with a launcher icon, label-sorted.
     *
     * Cached because querying takes a few hundred milliseconds and this sits in the path of
     * every "open X". [invalidate] on package install/uninstall broadcasts if that ever matters.
     */
    fun installedApps(): List<InstalledApp> = cache ?: load().also { cache = it }

    fun invalidate() {
        cache = null
    }

    private fun load(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val label = resolveInfo.loadLabel(packageManager).toString().trim()
                val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (label.isEmpty()) null else InstalledApp(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Best match for [query], or null when nothing is close enough to be worth launching. */
    fun resolve(query: String): InstalledApp? =
        FuzzyMatcher.best(query, installedApps()) { it.label }
}
