package com.nova.feature.accessibility

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.RequiredPermission
import com.nova.core.agent.screen.ScreenReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes everything that requires reaching into another app's UI.
 *
 * Registered after `DeviceActionExecutor`, which owns the actions a plain app can do on its own
 * (launching, torch, volume, brightness, home). Each action has exactly one owner — no action is
 * claimed by both — so the order in the container decides nothing subtle.
 *
 * When the service is off, every action here answers with [ActionResult.NeedsPermission] rather
 * than a failure, which is what lets the UI offer a button to the settings screen instead of a
 * dead end.
 */
class AccessibilityActionExecutor(
    private val screenReader: ScreenReader,
) : ActionExecutor {

    override val name: String = "accessibility"

    override fun canHandle(action: NovaAction): Boolean = when (action) {
        NovaAction.GoBack,
        NovaAction.LockScreen,
        NovaAction.TakeScreenshot,
        NovaAction.OpenRecents,
        NovaAction.OpenNotifications,
        NovaAction.ReadScreen,
        is NovaAction.TapLabel,
        is NovaAction.ScrollScreen,
        is NovaAction.TypeText,
        is NovaAction.CloseApp,
        -> true

        else -> false
    }

    override suspend fun execute(action: NovaAction): ActionResult = withContext(Dispatchers.Main) {
        val service = NovaAccessibilityService.connected
            ?: return@withContext ActionResult.NeedsPermission(
                RequiredPermission.ACCESSIBILITY_SERVICE,
                "Turn on Raza's accessibility service and I can do that.",
            )

        when (action) {
            NovaAction.GoBack -> service.goBack().toResult("Went back.", "I couldn't go back.")

            NovaAction.OpenRecents ->
                service.openRecents().toResult("Here are your recent apps.", "I couldn't open recents.")

            NovaAction.OpenNotifications ->
                service.openNotifications().toResult("Notifications.", "I couldn't open notifications.")

            NovaAction.LockScreen -> when (service.lockScreen()) {
                true -> ActionResult.Success("Locking.")
                false -> ActionResult.Failure("I couldn't lock the screen.")
                null -> ActionResult.Failure("Locking the screen needs Android 9 or newer.")
            }

            NovaAction.TakeScreenshot -> when (service.takeScreenshot()) {
                true -> ActionResult.Success("Screenshot taken.")
                false -> ActionResult.Failure("I couldn't take a screenshot.")
                null -> ActionResult.Failure("Screenshots need Android 11 or newer.")
            }

            NovaAction.ReadScreen -> screenReader.snapshot()
                ?.let { ActionResult.Success(it.spokenSummary()) }
                ?: ActionResult.Failure("I can't read this screen right now.")

            is NovaAction.TapLabel -> tap(service, action)

            is NovaAction.ScrollScreen -> service.scroll(action.direction)
                .toResult("Scrolled.", "There's nothing to scroll here.")

            is NovaAction.TypeText -> service.type(action.text)
                .toResult("Typed it.", "I couldn't find a text field to type into.")

            is NovaAction.CloseApp -> closeApp(service, action)

            else -> ActionResult.Unhandled(action)
        }
    }

    /** Confirms the label out loud — a fuzzy match that hits the wrong control must be audible. */
    private fun tap(
        service: NovaAccessibilityService,
        action: NovaAction.TapLabel,
    ): ActionResult = when (val result = service.tapLabel(action.label)) {
        is NovaAccessibilityService.TapResult.Tapped ->
            ActionResult.Success("Tapped ${result.label}.")

        is NovaAccessibilityService.TapResult.NotClickable ->
            ActionResult.Failure("I found ${result.label}, but it isn't tappable.")

        NovaAccessibilityService.TapResult.NotFound ->
            ActionResult.Failure("I couldn't find ${action.label} on screen.")

        NovaAccessibilityService.TapResult.NoWindow ->
            ActionResult.Failure("I can't read this screen — it may be a secure one.")
    }

    /**
     * Android has no API for closing another app; force-stop is reserved for the system, a
     * device owner, or root. Going home is the honest nearest thing, and the reply says so
     * rather than implying the app was killed.
     */
    private fun closeApp(
        service: NovaAccessibilityService,
        action: NovaAction.CloseApp,
    ): ActionResult {
        val what = action.query.takeIf { it.isNotBlank() } ?: "it"
        return service.goHome()
            .toResult("Sent $what to the background.", "I couldn't leave that app.")
    }

    private fun Boolean.toResult(success: String, failure: String): ActionResult =
        if (this) ActionResult.Success(success) else ActionResult.Failure(failure)
}
