package com.nova.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nova.core.agent.ActionExecutor
import com.nova.core.agent.ActionResult
import com.nova.core.agent.NovaAction
import com.nova.core.agent.diagnostics.Check
import com.nova.core.agent.diagnostics.CheckStatus
import com.nova.core.agent.diagnostics.DiagnosticReport
import com.nova.feature.accessibility.NovaAccessibilityService
import com.nova.feature.localllm.ModelStatus
import com.nova.feature.notifications.NovaNotificationListener

/**
 * Answers "what's wrong with you".
 *
 * Raza depends on six separately-granted permissions, two bound system services, an optional
 * model file and an optional API key. Any one of them being off makes a capability silently
 * decline, and until now the only way to find out which was a developer with a USB cable —
 * every problem in this project was diagnosed that way.
 *
 * Lives in the app module because it is the only place that can see all of those at once.
 */
class DiagnosticsActionExecutor(
    context: Context,
    private val container: NovaContainer,
) : ActionExecutor {

    private val appContext = context.applicationContext

    override val name: String = "diagnostics"

    override fun canHandle(action: NovaAction): Boolean = action is NovaAction.RunDiagnostics

    override suspend fun execute(action: NovaAction): ActionResult {
        val report = DiagnosticReport(
            listOf(
                microphone(),
                accessibility(),
                notifications(),
                systemSettings(),
                contacts(),
                planner(),
                alwaysListening(),
                stored(),
            ),
        )

        return ActionResult.Success(report.spoken())
    }

    private fun microphone() = check(
        name = "Microphone",
        granted = granted(Manifest.permission.RECORD_AUDIO),
        problem = "I don't have microphone access, so I can't hear you",
    )

    private fun accessibility() = check(
        name = "Accessibility",
        granted = NovaAccessibilityService.connected != null ||
            NovaAccessibilityService.isEnabled(appContext),
        // Names the cause, because this one switches itself off on every app update and the
        // user has no reason to suspect that.
        problem = "my accessibility service is off, so I can't tap or scroll in other apps. " +
            "It turns itself off whenever the app updates",
    )

    private fun notifications() = check(
        name = "Notifications",
        granted = NovaNotificationListener.isEnabled(appContext),
        problem = "I don't have notification access, so I can't read your notifications",
    )

    private fun systemSettings() = check(
        name = "System settings",
        granted = Settings.System.canWrite(appContext),
        problem = "I can't change system settings, so brightness commands won't work",
    )

    private fun contacts() = check(
        name = "Contacts",
        granted = granted(Manifest.permission.READ_CONTACTS),
        problem = "I don't have contacts access, so I can't call or message anyone",
    )

    /**
     * Optional rather than a problem: rules handle the everyday commands with no model and no
     * key at all, so nagging about a missing planner would be wrong.
     */
    private fun planner(): Check {
        val model = container.localModels.status()
        val hasKey = container.apiKeys.hasKey()

        return when {
            model is ModelStatus.Ready -> Check(
                "Multi-step tasks",
                CheckStatus.OK,
                "on-device model, ${model.sizeBytes / 1_048_576} MB",
            )

            model is ModelStatus.TooLittleMemory -> Check(
                "Multi-step tasks",
                CheckStatus.NEEDS_ACTION,
                "the on-device model won't fit in free memory right now",
            )

            hasKey -> Check("Multi-step tasks", CheckStatus.OK, "using the API key")

            else -> Check(
                "Multi-step tasks",
                CheckStatus.OPTIONAL,
                "no model or API key — everyday commands still work",
            )
        }
    }

    private fun alwaysListening() = Check(
        name = "Always listening",
        status = CheckStatus.OPTIONAL,
        detail = if (NovaListeningService.isRunning) "running" else "off",
    )

    private suspend fun stored() = Check(
        name = "Stored",
        status = CheckStatus.OPTIONAL,
        detail = "${container.memory.all().size} memories, ${container.routines.all().size} routines",
    )

    private fun check(name: String, granted: Boolean, problem: String) = Check(
        name = name,
        status = if (granted) CheckStatus.OK else CheckStatus.NEEDS_ACTION,
        detail = if (granted) "granted" else problem,
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
