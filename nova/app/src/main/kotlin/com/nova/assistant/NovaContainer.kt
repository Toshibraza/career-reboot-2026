package com.nova.assistant

import android.content.Context
import com.nova.core.agent.ActiveCommands
import com.nova.core.agent.AgentContext
import com.nova.core.agent.AgentRuntime
import com.nova.core.agent.SpeakActionExecutor
import com.nova.core.agent.rules.RuleIntentEngine
import com.nova.core.agent.task.TaskPlanner
import com.nova.core.llm.ChatClient
import com.nova.core.llm.OpenAiClient
import com.nova.core.llm.ModelUnavailableException
import com.nova.core.llm.RateLimitedChatClient
import com.nova.core.llm.spokenLlmFailure
import java.io.IOException
import com.nova.core.llm.ResponseSchema
import com.nova.core.llm.ConversationActionExecutor
import com.nova.core.llm.LlmTaskPlanner
import com.nova.feature.localllm.LocalChatClient
import com.nova.feature.localllm.LocalModelStore
import com.nova.core.speech.AndroidSpeaker
import com.nova.core.speech.AndroidSpeechToText
import com.nova.core.speech.EchoGuard
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
import com.nova.core.agent.search.WebSearch
import com.nova.core.search.ApifyWebSearch
import com.nova.core.search.SearchActionExecutor
import com.nova.core.agent.help.CapabilitiesActionExecutor
import com.nova.feature.device.AndroidUrlOpener
import com.nova.feature.device.MediaActionExecutor
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

    /**
     * One instance, shared by the mouth and both ears.
     *
     * The speaker records what was said; the wake detector and the command capture check
     * against it. Two instances would mean each ear only knew about speech it had not heard.
     */
    val echoGuard: EchoGuard = EchoGuard()

    val speaker: Speaker by lazy {
        AndroidSpeaker(
            appContext,
            preferredVoiceId = { voicePreference.current() },
            echoGuard = echoGuard,
        )
    }

    /**
     * Gated rather than polling: the recogniser only starts once raw microphone energy
     * suggests someone is actually speaking. See [GatedWakeWordDetector] for what this still
     * does not solve.
     */
    /**
     * Continuous, not gated.
     *
     * The gate saved battery by only starting recognition once someone spoke — and in doing so
     * guaranteed the wake word was already over by the time anything was listening. Every wake
     * attempt on this device failed with NO_MATCH for that reason. Hearing the user is worth
     * more than the battery it saves.
     */
    val wakeWordDetector: WakeWordDetector by lazy {
        GatedWakeWordDetector(speechToText, gate = null, echoGuard = echoGuard)
    }

    private val appRegistry by lazy { AppRegistry(appContext) }

    private val deviceController by lazy { DeviceController(appContext) }

    /** Shared by playing media and by search when no API token is configured. */
    private val urlOpener by lazy { AndroidUrlOpener(appContext) }

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
     * Every command currently running, whichever entry point started it: the screen, the
     * wake word, a routine. The orb's stop action cancels through this, so a task started by
     * voice is as stoppable as one started by touch.
     */
    val activeCommands = ActiveCommands()

    /**
     * Web search. Token read per call, so pasting one takes effect on the next command.
     *
     * Apify's own repository is a set of skills for AI coding agents — markdown telling a
     * developer's agent how to drive their CLI — so none of it is consumable from Android.
     * This calls the same Actors over their REST API instead.
     */
    val webSearch: WebSearch by lazy { ApifyWebSearch(token = { apiKeys.apifyToken() }) }

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
     * A model served from the local network, if one is configured.
     *
     * LM Studio, Ollama and llama.cpp's server all speak the OpenAI chat API, so the existing
     * client reaches them by changing one URL. The point is capacity: this phone reports 5.7 GB
     * of RAM and under 2 GB actually available, which rules out anything past a small model,
     * while a desktop on the same WiFi runs an 8B comfortably.
     *
     * Not rate limited, unlike the API. The limiter exists because requests cost money and a
     * routine could fire a task unattended; a machine in the next room costs electricity.
     *
     * The model name is whatever the server has loaded. LM Studio serves the loaded model
     * regardless of what is asked for, so this is a label rather than a selector.
     */
    private val lanClient: ChatClient? by lazy {
        BuildConfig.LLM_SERVER_URL.trim().takeIf { it.isNotEmpty() }?.let { base ->
            OpenAiClient(
                apiKey = { "local" },
                model = "local-model",
                endpoint = "${base.trimEnd('/')}/v1/chat/completions",
                // Generous next to the API's 30s: an 8B model on a desktop CPU takes its time
                // over a first token, and the failure to avoid is giving up on a good answer.
                timeoutMillis = 120_000,
            )
        }
    }

    /**
     * Gemma on the phone is the model. The API is a stand-in for when it is missing.
     *
     * Chosen per request, so side-loading a model takes effect on the next command.
     *
     * A local failure deliberately does **not** fall back to the cloud. Someone who installed a
     * model on their phone did it so their screen contents stay on their phone; quietly posting
     * that screen to an API because the local model ran out of memory would betray exactly the
     * choice they made. The failure is reported instead.
     *
     * When Gemma is absent the API's own diagnosis is kept and the missing model is named after
     * it. "My OpenAI account is out of credit" is true but points at the wrong repair: adding
     * credit to an account is not what this assistant is supposed to need, and someone hearing
     * only that would go and pay for it.
     */
    private val chatClient: ChatClient = object : ChatClient {
        override suspend fun complete(system: String, user: String, schema: ResponseSchema?): String {
            if (localModels.isInstalled()) return localClient.complete(system, user, schema)

            // Before the API but after the phone's own model: still the user's hardware and
            // still nothing leaving their network, and far larger than the phone can hold.
            lanClient?.let { return it.complete(system, user, schema) }

            if (!apiKeys.hasKey()) throw ModelUnavailableException(NO_MODEL)

            return try {
                cloudClient.complete(system, user, schema)
            } catch (failure: IOException) {
                throw ModelUnavailableException("${failure.spokenLlmFailure()} $NO_MODEL")
            }
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
                DiagnosticsActionExecutor(appContext, localModels, apiKeys, memory, routines),
                SearchActionExecutor(webSearch, urlOpener),
                MediaActionExecutor(urlOpener),
                CapabilitiesActionExecutor(),
                // Registered before the routine and notification executors only for
                // readability; every action still has exactly one owner.
                ConversationActionExecutor(chatClient, memory, webSearch),
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

    private companion object {
        /**
         * Said aloud, so it names the one action that fixes this rather than describing the
         * problem. The path is the app's own external directory, which is writable over adb
         * without root — and, unavoidably, is also wiped by an uninstall.
         */
        const val NO_MODEL =
            "Raza runs on Gemma on this phone. Push a Gemma task file to Android, data, " +
                "com dot nova dot assistant, files, l l m, model dot task."
    }

}
