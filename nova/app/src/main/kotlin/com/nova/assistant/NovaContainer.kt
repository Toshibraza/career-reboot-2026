package com.nova.assistant

import android.content.Context
import com.nova.core.agent.AgentContext
import com.nova.core.agent.AgentRuntime
import com.nova.core.agent.SpeakActionExecutor
import com.nova.core.agent.rules.RuleIntentEngine
import com.nova.core.speech.AndroidSpeaker
import com.nova.core.speech.AndroidSpeechToText
import com.nova.core.speech.Speaker
import com.nova.core.speech.SpeechToText
import com.nova.core.speech.TranscriptWakeWordDetector
import com.nova.core.speech.WakeWordDetector
import com.nova.feature.device.AppRegistry
import com.nova.feature.device.DeviceActionExecutor
import com.nova.feature.device.DeviceController
import com.nova.feature.device.UnsupportedActionExecutor

/**
 * The composition root — the one place that knows which implementation of each interface Nova
 * is running with.
 *
 * Swapping the rule engine for an LLM, or the platform recogniser for Whisper, is an edit to
 * this file and nowhere else. Hand-rolled rather than Hilt: at this size the wiring is shorter
 * than the annotations, and it stays readable as the module list grows.
 */
class NovaContainer(context: Context) {

    private val appContext = context.applicationContext

    val speechToText: SpeechToText by lazy { AndroidSpeechToText(appContext) }

    val speaker: Speaker by lazy { AndroidSpeaker(appContext) }

    val wakeWordDetector: WakeWordDetector by lazy { TranscriptWakeWordDetector(speechToText) }

    private val appRegistry by lazy { AppRegistry(appContext) }

    private val deviceController by lazy { DeviceController(appContext) }

    val runtime: AgentRuntime by lazy {
        AgentRuntime(
            intentEngine = RuleIntentEngine(),
            // Order matters: the real executor gets first refusal, and the
            // not-yet-implemented one only sees what nothing else claimed.
            executors = listOf(
                DeviceActionExecutor(deviceController, appRegistry),
                SpeakActionExecutor(),
                UnsupportedActionExecutor(),
            ),
            contextProvider = {
                AgentContext(installedAppLabels = appRegistry.installedApps().map { it.label })
            },
        )
    }
}
