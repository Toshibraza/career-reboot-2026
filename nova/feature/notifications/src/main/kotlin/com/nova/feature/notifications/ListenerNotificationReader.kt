package com.nova.feature.notifications

import android.content.Context
import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.RequiredPermission
import com.nova.core.agent.notifications.NotificationAccess
import com.nova.core.agent.notifications.NotificationReader
import com.nova.core.agent.notifications.NovaNotification
import com.nova.core.agent.notifications.summarise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [NotificationReader] over the bound [NovaNotificationListener]. */
class ListenerNotificationReader(context: Context) : NotificationReader {

    private val appContext = context.applicationContext

    override val availability: NotificationAccess
        get() = when {
            NovaNotificationListener.connected != null -> NotificationAccess.READY
            // Granted in settings but not bound yet — Android rebinds a moment after a
            // restart, and asking for permission again in that window would be wrong.
            NovaNotificationListener.isEnabled(appContext) -> NotificationAccess.NOT_CONNECTED
            else -> NotificationAccess.NOT_GRANTED
        }

    override suspend fun current(): List<NovaNotification> = withContext(Dispatchers.Default) {
        NovaNotificationListener.connected?.snapshot().orEmpty()
    }
}

/**
 * Reads the shade aloud.
 *
 * Keeps "nothing new", "not granted" and "not connected yet" apart. Collapsing the first two
 * would let a missing permission look like an empty inbox — the one wrong answer that really
 * matters here — and collapsing the last two tells the user to grant access they already gave.
 */
class NotificationActionExecutor(
    private val reader: NotificationReader,
) : ActionExecutor {

    override val name: String = "notifications"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.ReadNotifications

    override suspend fun execute(action: NovaAction): ActionResult =
        when (reader.availability) {
            NotificationAccess.NOT_GRANTED -> ActionResult.NeedsPermission(
                RequiredPermission.NOTIFICATION_LISTENER,
                "Give Raza notification access and I can read them.",
            )

            NotificationAccess.NOT_CONNECTED -> ActionResult.Failure(
                "I can't reach your notifications just yet. Try again in a moment.",
            )

            NotificationAccess.READY -> ActionResult.Success(reader.current().summarise())
        }
}
