package com.nova.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nova.core.agent.ActionResult
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

        // Released immediately rather than held for the duration. A broadcast has a hard
        // completion deadline, and an on-device planning loop can run well past it — eight
        // steps at fourteen seconds each ANR'd the receiver outright. The work continues on a
        // scope tied to the application instead, which has no such limit and matches how the
        // real voice path runs it.
        scope.launch {
            val response = container.runtime.handle(command)
            // Nova's answer is otherwise only spoken, which is invisible to adb. Without this,
            // a command that quietly declined looks identical to one that worked.
            Log.i(TAG, "\"$command\" -> ${response.plan.actions} -> ${response.spoken}")

            // The runtime turns an executor throwable into a plain "That didn't work", which
            // is right for the user and useless for debugging. Without this the cause is
            // dropped, and an unexpected exception looks identical to a handled refusal.
            response.results
                .filterIsInstance<ActionResult.Failure>()
                .mapNotNull { it.cause }
                .forEach { Log.w(TAG, "action threw", it) }

            container.speaker.speak(response.spoken)
        }.also { container.activeCommands.track(it) }
    }

    private companion object {
        const val EXTRA_COMMAND = "command"
        const val TAG = "NovaCommand"

        /** Outlives any single broadcast. Accessibility work has to run on the main thread. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
