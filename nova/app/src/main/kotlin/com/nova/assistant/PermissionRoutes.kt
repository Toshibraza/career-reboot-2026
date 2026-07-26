package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.nova.core.agent.RequiredPermission

/**
 * Opens the settings screen that grants [permission].
 *
 * None of these can be requested with a runtime dialog — each one is a system screen the user
 * has to visit. Sending them straight to the right page is the difference between a feature
 * people turn on and one they give up on.
 */
/**
 * Opens the screen where the device's assistant app is chosen.
 *
 * The exact activity varies by OEM, so this tries the specific picker first and falls back to
 * the app's own details page rather than dead-ending on a device that lacks it.
 */
fun Context.openAssistantSettings() {
    val candidates = listOf(
        Intent("android.settings.VOICE_INPUT_SETTINGS"),
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    for (intent in candidates) {
        val started = runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (started) return
    }
}

fun Context.openSettingsFor(permission: RequiredPermission) {
    val packageUri = Uri.fromParts("package", packageName, null)

    val intent = when (permission) {
        RequiredPermission.WRITE_SYSTEM_SETTINGS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri)

        RequiredPermission.DO_NOT_DISTURB ->
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        RequiredPermission.ACCESSIBILITY_SERVICE ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        RequiredPermission.NOTIFICATION_LISTENER ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        RequiredPermission.USAGE_STATS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        // Runtime permissions the user has permanently denied, plus device admin, all end up
        // on the app's own details page.
        RequiredPermission.RECORD_AUDIO,
        RequiredPermission.CAMERA,
        RequiredPermission.DEVICE_ADMIN,
        // Contacts, calling and SMS are runtime permissions, so the app asks for them with a
        // dialog. Landing here means the user chose "don't ask again", and the app details
        // page is the only remaining route.
        RequiredPermission.READ_CONTACTS,
        RequiredPermission.CALL_PHONE,
        RequiredPermission.SEND_SMS,
        -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }

    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
