package com.nova.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nova.assistant.NovaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs a command over adb without bringing Nova to the foreground:
 *
 * ```
 * adb shell am broadcast -a com.nova.assistant.COMMAND -e command "'scroll down'"
 * ```
 *
 * This exists because the activity's `-e command` hook can't test cross-app control: starting
 * MainActivity makes Nova the foreground app, so "tap send" would search Nova's own screen
 * rather than the app the user is actually looking at. Broadcasting leaves the foreground
 * alone, which is also how the always-listening service behaves in real use.
 *
 * Debug source set only — this class and its manifest entry do not exist in a release build.
 */
class NovaCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() } ?: return
        val container = (context.applicationContext as NovaApplication).container

        // goAsync keeps the process alive past onReceive; without it the agent would be killed
        // mid-command. Accessibility work has to run on the main thread.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val response = container.runtime.handle(command)
                // Nova's answer is otherwise only spoken, which is invisible to adb. Without
                // this, a command that quietly declined looks identical to one that worked.
                Log.i(TAG, "\"$command\" -> ${response.plan.actions} -> ${response.spoken}")
                container.speaker.speak(response.spoken)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val EXTRA_COMMAND = "command"
        const val TAG = "NovaCommand"
    }
}
