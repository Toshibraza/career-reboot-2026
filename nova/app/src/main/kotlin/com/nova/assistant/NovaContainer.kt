package com.nova.assistant

import android.content.Context
import com.nova.core.agent.AgentContext
import com.nova.core.agent.AgentRuntime
import com.nova.core.agent.SpeakActionExecutor
import com.nova.core.agent.rules.RuleIntentEngine
import com.nova.core.agent.task.TaskPlanner
import com.nova.core.llm.OpenAiClient
import com.nova.core.llm.OpenAiTaskPlanner
import com.nova.core.speech.AndroidSpeaker
import com.nova.core.speech.AndroidSpeechToText
import com.nova.core.speech.Speaker
import com.nova.core.speech.SpeechToText
import com.nova.core.speech.TranscriptWakeWordDetector
import com.nova.core.speech.WakeWordDetector
import com.nova.core.agent.screen.ScreenReader
import com.nova.feature.accessibility.AccessibilityActionExecutor
import com.nova.feature.accessibility.AccessibilityScreenReader
import com.nova.feature.device.AppRegistry
import com.nova.feature.device.DeviceActionExecutor
import com.nova.feature.device.DeviceController

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

    /** Nova's eyes. Swapped for an OCR-backed reader later without touching anything else. */
    val screenReader: ScreenReader by lazy { AccessibilityScreenReader(appContext) }

    val apiKeys: ApiKeyStore by lazy { ApiKeyStore(appContext) }

    /**
     * Drives multi-step tasks the rule engine can't parse.
     *
     * Always constructed, because the key is read per request rather than captured here — a
     * key pasted into the app takes effect on the next command, with no restart. Without any
     * key the planner says so plainly instead of the runtime silently declining.
     */
    private val taskPlanner: TaskPlanner by lazy {
        OpenAiTaskPlanner(OpenAiClient(apiKey = { apiKeys.current() }))
    }

    val runtime: AgentRuntime by lazy {
        AgentRuntime(
            taskPlanner = taskPlanner,
            intentEngine = RuleIntentEngine(),
            // Every action has exactly one owner, so this order is documentation rather than
            // precedence: device does what a plain app can, accessibility does what needs to
            // reach into other apps.
            executors = listOf(
                DeviceActionExecutor(deviceController, appRegistry),
                AccessibilityActionExecutor(screenReader),
                SpeakActionExecutor(),
            ),
            contextProvider = {
                AgentContext(
                    installedAppLabels = appRegistry.installedApps().map { it.label },
                    // Passed as a provider, not a value: the rule engine never calls it, so
                    // an ordinary "open YouTube" reads nothing off the user's screen.
                    screenProvider = { screenReader.snapshot() },
                )
            },
        )
    }
}
