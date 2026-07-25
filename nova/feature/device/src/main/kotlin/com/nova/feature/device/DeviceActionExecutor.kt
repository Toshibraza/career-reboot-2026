package com.nova.feature.device

import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.LevelChange
import com.nova.core.agent.NovaAction
import com.nova.core.agent.RequiredPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes the actions a plain app can perform without special privileges.
 *
 * The actions this deliberately refuses — back, close, lock, screenshot — are the ones Android
 * only exposes to an AccessibilityService. They are declined with a straight answer rather than
 * a silent no-op, and picked up by the Phase 2 accessibility executor registered alongside this
 * one.
 */
class DeviceActionExecutor(
    private val controller: DeviceController,
    private val apps: AppRegistry,
) : ActionExecutor {

    override val name: String = "device"

    override fun canHandle(action: NovaAction): Boolean = when (action) {
        is NovaAction.OpenApp,
        is NovaAction.SetFlashlight,
        is NovaAction.SetVolume,
        is NovaAction.SetBrightness,
        NovaAction.GoHome,
        -> true

        else -> false
    }

    override suspend fun execute(action: NovaAction): ActionResult = withContext(Dispatchers.IO) {
        runCatching {
            when (action) {
                is NovaAction.OpenApp -> openApp(action.query)
                is NovaAction.SetFlashlight -> setTorch(action.on)
                is NovaAction.SetVolume -> setVolume(action)
                is NovaAction.SetBrightness -> setBrightness(action.level)
                NovaAction.GoHome -> {
                    controller.goHome()
                    ActionResult.Success()
                }
                else -> ActionResult.Unhandled(action)
            }
        }.getOrElse { throwable ->
            ActionResult.Failure(throwable.message ?: "That didn't work.", throwable)
        }
    }

    private fun openApp(query: String): ActionResult {
        val app = apps.resolve(query)
            ?: return ActionResult.Failure("I couldn't find an app called $query.")
        controller.launch(app.packageName)
        return ActionResult.Success("Opening ${app.label}.")
    }

    private fun setTorch(on: Boolean): ActionResult {
        if (!controller.hasTorch()) {
            return ActionResult.Failure("This phone doesn't have a flash.")
        }
        controller.setTorch(on)
        return ActionResult.Success(if (on) "Flashlight on." else "Flashlight off.")
    }

    private fun setVolume(action: NovaAction.SetVolume): ActionResult {
        val result = try {
            controller.setVolume(action.stream, action.level)
        } catch (security: SecurityException) {
            // Silencing the ringer needs Do Not Disturb access on Android 7+.
            return ActionResult.NeedsPermission(
                RequiredPermission.DO_NOT_DISTURB,
                "I need Do Not Disturb access to change the ringer.",
                )
        }
        return ActionResult.Success(
            when (action.level) {
                LevelChange.Min -> "Muted."
                else -> "Volume $result percent."
            },
        )
    }

    private fun setBrightness(level: LevelChange): ActionResult {
        if (!controller.canWriteSettings()) {
            return ActionResult.NeedsPermission(
                RequiredPermission.WRITE_SYSTEM_SETTINGS,
                "I need permission to change system settings for that.",
            )
        }
        val result = controller.setBrightness(level)
        return ActionResult.Success("Brightness $result percent.")
    }
}

/**
 * Answers for the actions that need an AccessibilityService, until Phase 2 builds one.
 *
 * Registered last, after [DeviceActionExecutor], so it only ever sees what nothing else claimed.
 * Its whole job is to make "lock the phone" say why it can't rather than fail silently — and to
 * be deleted the day `:feature:accessibility` lands.
 */
class UnsupportedActionExecutor : ActionExecutor {

    override val name: String = "not-yet-implemented"

    override fun canHandle(action: NovaAction): Boolean = when (action) {
        NovaAction.GoBack,
        NovaAction.LockScreen,
        NovaAction.TakeScreenshot,
        is NovaAction.CloseApp,
        -> true

        else -> false
    }

    override suspend fun execute(action: NovaAction): ActionResult = ActionResult.Failure(
        "That needs the accessibility service, which isn't built yet.",
    )
}
