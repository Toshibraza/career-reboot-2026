package com.nova.feature.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nova.core.agent.notifications.NovaNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads the notification shade when asked.
 *
 * Nothing is stored. [onNotificationPosted] is deliberately not overridden to collect a
 * history: notifications are other people's messages as much as the user's, and keeping a
 * transcript of them would be a liability with no matching benefit. Everything is pulled live
 * from [getActiveNotifications] at the moment the user asks.
 */
class NovaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        binding.value = this
    }

    override fun onListenerDisconnected() {
        if (binding.value === this) binding.value = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (binding.value === this) binding.value = null
        super.onDestroy()
    }

    /** Live snapshot of the shade, newest first, with the noise filtered out. */
    fun snapshot(): List<NovaNotification> = runCatching {
        activeNotifications.orEmpty()
            .filter { it.isWorthSaying() }
            .sortedByDescending { it.postTime }
            .mapNotNull { it.toNova() }
            .distinctBy { it.appLabel to it.title to it.text }
    }.getOrDefault(emptyList())

    /**
     * Filters the shade down to things a person would want read out.
     *
     * Ongoing notifications are the music player, the navigation bar, Nova's own listening
     * notice — they are status, not news, and reading them back every time would bury the one
     * message that mattered. Group summaries duplicate their children.
     */
    private fun StatusBarNotification.isWorthSaying(): Boolean {
        if (isOngoing) return false
        if (packageName == this@NovaNotificationListener.packageName) return false

        val flags = notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        return true
    }

    private fun StatusBarNotification.toNova(): NovaNotification? {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()

        // Nothing readable — an icon-only or fully custom notification.
        if (title.isEmpty() && text.isEmpty()) return null

        return NovaNotification(
            appLabel = appLabelOf(packageName),
            title = title,
            text = text,
            postedAt = postTime,
        )
    }

    private fun appLabelOf(packageName: String): String = runCatching {
        val info = this.packageManager.getApplicationInfo(packageName, 0)
        this.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    companion object {
        private val binding = MutableStateFlow<NovaNotificationListener?>(null)

        val connection: StateFlow<NovaNotificationListener?> = binding.asStateFlow()

        val connected: NovaNotificationListener? get() = binding.value

        /**
         * Whether the user has granted notification access.
         *
         * Read from Settings.Secure rather than checking [connected], because the service can
         * be enabled but not yet bound and the UI should not nag in that window.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, NovaNotificationListener::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false

            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
