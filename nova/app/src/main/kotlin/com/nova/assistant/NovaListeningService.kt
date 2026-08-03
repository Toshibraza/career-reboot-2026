package com.nova.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nova.core.agent.NovaAction
import com.nova.core.speech.SpeechEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Keeps the wake word detector running while Nova is not on screen.
 *
 * A foreground service is not optional here: Android stops background microphone access within
 * seconds otherwise, and from Android 14 the `microphone` service type is required to hold it.
 *
 * Known Phase 1 limitation: acting on a command from here can be blocked by the background
 * activity-start restrictions on Android 10+ — "open YouTube" spoken with the screen off may do
 * nothing. Phase 2's accessibility service removes that constraint, since it can start
 * activities on the user's behalf.
 */
class NovaListeningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container by lazy { (application as NovaApplication).container }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundWithNotification()
        listenForWakeWord()
    }

    private fun listenForWakeWord() {
        scope.launch {
            Log.i(TAG, "listening service started")
            runCatching {
                container.wakeWordDetector.detections().collect {
                    handleOneCommand()
                }
            }.onFailure { Log.w(TAG, "wake word loop stopped", it) }
        }
    }

    /**
     * One waking, then as many follow-ups as the user keeps giving.
     *
     * Requiring the wake word before every single command is not how people talk to this. The
     * device log is unambiguous: after "open YouTube" succeeded, the next three things said
     * were "minimise YouTube", "close YouTube" and "close YouTube" — every one of them a
     * command, every one discarded for not containing "Raza".
     *
     * The window closes on the first thing that is not a command, so an assistant left alone in
     * a room falls back to needing its name rather than acting on the conversation around it.
     */
    private suspend fun handleOneCommand() {
        container.speaker.speak("Yes?")

        var turn = 0
        while (turn < MAX_TURNS) {
            val utterance = listenForCommand()

            if (utterance == null) {
                // Only worth saying the first time. After a command has run, silence means the
                // user has finished, and announcing that would be Raza talking to itself.
                if (turn == 0) container.speaker.speak("I didn't catch that.")
                return
            }

            Log.i(TAG, "command: \"$utterance\"")

            // Repeated back before acting. With no screen in front of them the user has no
            // other way to know what was understood, and hearing the wrong command before it
            // happens is the difference between catching a mistake and discovering it after.
            container.speaker.speak("You said, $utterance")

            if (!runCommand(utterance)) return
            turn++
        }
    }

    /** Null when nothing usable was heard: silence, no match, or Raza's own voice. */
    private suspend fun listenForCommand(): String? {
        // The speaker reports "done" when it stops writing audio, not when the room stops
        // carrying it. Opening the microphone on that same instant is what makes Raza hear its
        // own prompt. The wake detector already waits this long between microphone handovers;
        // this path was the one that did not.
        delay(MIC_SETTLE_MILLIS)

        val heard = container.speechToText.transcribe()
            .mapNotNull { (it as? SpeechEvent.Final)?.text }
            .firstOrNull()

        if (heard.isNullOrBlank()) {
            Log.i(TAG, "heard no command")
            return null
        }

        if (container.echoGuard.isEcho(heard)) {
            // Not spoken to. Answering would say something new, which would be heard in turn —
            // the loop this exists to break.
            Log.i(TAG, "ignored \"$heard\" — that was Raza hearing itself")
            return null
        }

        return heard
    }

    /** False when the follow-up window should close. */
    private suspend fun runCommand(utterance: String): Boolean = coroutineScope {
        var understood = false

        // The command runs as a child job registered with the container, so the orb's stop
        // control can cancel it. Cancelling the child ends the command; the wake-word loop
        // above survives and keeps listening.
        val command = launch {
            val response = container.runtime.handle(utterance)
            Log.i(TAG, "-> ${response.spoken}")

            // Only a command Raza actually understood keeps the microphone open. Anything else
            // is as likely to be the room as the user, and staying open would turn overheard
            // conversation into actions.
            understood = response.plan.actions.none { it is NovaAction.Unsupported }

            container.speaker.speak(response.spoken)
        }
        container.activeCommands.track(command)
        command.join()

        understood
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.listening_channel_name),
                // Low: this notification is a legal requirement, not news.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.listening_notification_title))
            .setContentText(getString(R.string.listening_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        container.speaker.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NovaWake"
        private const val CHANNEL_ID = "nova-listening"
        private const val NOTIFICATION_ID = 1

        /**
         * Pause between Raza finishing a line and the microphone opening.
         *
         * Matches the handover delay the wake detector already uses. Long enough for a speaker
         * to fall quiet, short enough that the user is not left waiting after "Yes?".
         */
        private const val MIC_SETTLE_MILLIS = 250L

        /**
         * Commands accepted per waking, including the first.
         *
         * A cap rather than "until silence" because each turn holds the microphone open, and a
         * run of recognised commands in a busy room should still end on its own. Six is more
         * than anyone strings together in one breath and short enough to be a bounded loop.
         */
        private const val MAX_TURNS = 6

        /**
         * Read by the UI to render the toggle. Good enough while exactly one activity and one
         * service exist; if a second entry point appears, move this to a StateFlow on the
         * container.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NovaListeningService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NovaListeningService::class.java))
        }
    }
}
