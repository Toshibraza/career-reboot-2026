package com.nova.assistant

import android.content.Context
import com.nova.core.agent.AgentContext
import com.nova.core.agent.AgentRuntime
import com.nova.core.agent.SpeakActionExecutor
import com.nova.core.agent.rules.RuleIntentEngine
import com.nova.core.agent.task.TaskPlanner
import com.nova.core.llm.ChatClient
import com.nova.core.llm.OpenAiClient
import com.nova.core.llm.RateLimitedChatClient
import com.nova.core.llm.LlmTaskPlanner
import com.nova.feature.localllm.LocalChatClient
import com.nova.feature.localllm.LocalModelStore
import com.nova.core.speech.AndroidSpeaker
import com.nova.core.speech.AndroidSpeechToText
import com.nova.core.speech.Speaker
import com.nova.core.speech.SpeechToText
import com.nova.core.speech.GatedWakeWordDetector
import com.nova.core.speech.WakeWordDetector
import com.nova.core.agent.comms.ConfirmationSlot
import com.nova.core.agent.memory.Memory
import com.nova.feature.comms.CommsActionExecutor
import com.nova.feature.comms.ContactDirectory
import com.nova.core.agent.notifications.NotificationReader
import com.nova.feature.notifications.ListenerNotificationReader
import com.nova.feature.notifications.NotificationActionExecutor
import com.nova.feature.vision.ScreenTextReader
import com.nova.feature.vision.VisionActionExecutor
import com.nova.core.agent.routine.RoutineStore
import com.nova.feature.routines.RoutineActionExecutor
import com.nova.feature.routines.RoutineScheduler
import com.nova.feature.routines.SqliteRoutineStore
import com.nova.core.agent.screen.ScreenReader
import com.nova.feature.memory.MemoryActionExecutor
import com.nova.feature.memory.SqliteMemory
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

    val voicePreference: VoicePreference by lazy { VoicePreference(appContext) }

    val speaker: Speaker by lazy {
        AndroidSpeaker(appContext, preferredVoiceId = { voicePreference.current() })
    }

    /**
     * Gated rather than polling: the recogniser only starts once raw microphone energy
     * suggests someone is actually speaking. See [GatedWakeWordDetector] for what this still
     * does not solve.
     */
    val wakeWordDetector: WakeWordDetector by lazy { GatedWakeWordDetector(speechToText) }

    private val appRegistry by lazy { AppRegistry(appContext) }

    private val deviceController by lazy { DeviceController(appContext) }

    /** Nova's eyes. Swapped for an OCR-backed reader later without touching anything else. */
    val screenReader: ScreenReader by lazy { AccessibilityScreenReader(appContext) }

    /** Everything Nova has been told to remember. Local, and never leaves the device. */
    val memory: Memory by lazy { SqliteMemory(appContext) }

    /**
     * Concrete rather than the interface, because power triggers need the arming operations.
     * Those are storage bookkeeping for one trigger kind, not something every implementation
     * of [RoutineStore] should have to answer for.
     */
    val routineStore: SqliteRoutineStore by lazy { SqliteRoutineStore(appContext) }

    val routines: RoutineStore get() = routineStore

    /** The shade, read live when asked. Nothing is kept. */
    val notifications: NotificationReader by lazy { ListenerNotificationReader(appContext) }

    /** OCR for what the node tree cannot see. Offline, and nothing is written to disk. */
    private val screenTextReader by lazy { ScreenTextReader() }

    private val contacts by lazy { ContactDirectory(appContext) }

    /**
     * Holds a proposed call or message until the user confirms it.
     *
     * Shared across the whole app so a confirmation can arrive through any route — the mic,
     * the text box, or the always-listening service.
     */
    private val confirmations = ConfirmationSlot()

    val routineScheduler: RoutineScheduler by lazy {
        RoutineScheduler(appContext, RoutineReceiver::class.java)
    }

    val apiKeys: ApiKeyStore by lazy { ApiKeyStore(appContext) }

    /**
     * Drives multi-step tasks the rule engine can't parse.
     *
     * Always constructed, because the key is read per request rather than captured here — a
     * key pasted into the app takes effect on the next command, with no restart. Without any
     * key the planner says so plainly instead of the runtime silently declining.
     */
    val localModels: LocalModelStore by lazy { LocalModelStore(appContext) }

    private val localClient: ChatClient by lazy { LocalChatClient(appContext, localModels) }

    /**
     * Wrapped in a rate limiter, unlike the local one.
     *
     * On-device inference costs only time, so rationing it would be friction. API calls cost
     * money, a single task makes up to eight of them, and a routine could fire a task on a
     * schedule while nobody is watching.
     */
    private val cloudClient: ChatClient by lazy {
        RateLimitedChatClient(OpenAiClient(apiKey = { apiKeys.current() }))
    }

    /**
     * On-device model if one is installed, otherwise the API.
     *
     * Chosen per request, so side-loading a model takes effect on the next command.
     *
     * A local failure deliberately does **not** fall back to the cloud. Someone who installed a
     * model on their phone did it so their screen contents stay on their phone; quietly posting
     * that screen to an API because the local model ran out of memory would betray exactly the
     * choice they made. The failure is reported instead.
     */
    private val chatClient: ChatClient = object : ChatClient {
        override suspend fun complete(system: String, user: String): String =
            if (localModels.isInstalled()) {
                localClient.complete(system, user)
            } else {
                cloudClient.complete(system, user)
            }
    }

    private val taskPlanner: TaskPlanner by lazy { LlmTaskPlanner(chatClient) }

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
                MemoryActionExecutor(memory),
                DiagnosticsActionExecutor(appContext, this),
                RoutineActionExecutor(routines, routineScheduler),
                NotificationActionExecutor(notifications),
                VisionActionExecutor(screenTextReader),
                CommsActionExecutor(appContext, contacts, confirmations),
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
