package com.nova.assistant.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nova.assistant.NovaApplication
import com.nova.assistant.NovaContainer
import com.nova.core.agent.ActionResult
import com.nova.core.agent.AgentResponse
import com.nova.core.agent.NovaAction
import com.nova.core.agent.RequiredPermission
import com.nova.core.agent.memory.MemoryEntry
import com.nova.core.agent.routine.Routine
import com.nova.core.agent.routine.RoutineTrigger
import com.nova.core.speech.SpeechError
import com.nova.core.speech.SpeechEvent
import com.nova.core.speech.VoiceOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NovaStatus { IDLE, LISTENING, THINKING, SPEAKING }

/** One exchange, kept for the on-screen transcript. */
data class Turn(
    val heard: String,
    val reply: String,
    val succeeded: Boolean,
    /**
     * What a multi-step task actually did, one line per step.
     *
     * Empty for ordinary commands. A planner-driven task is otherwise a black box — it taps
     * around for several seconds and then says one sentence — and when it goes wrong the steps
     * are the only way to see where.
     */
    val steps: List<String> = emptyList(),
)

/**
 * What Raza is holding, for the user to inspect and correct.
 *
 * Null when closed. Memory and routines are otherwise write-only by voice: a misheard fact or a
 * reminder set for the wrong time can be created but never seen, and "forget my parking spot"
 * only helps if you remember it went in wrong.
 */
data class LibraryState(
    val memories: List<MemoryEntry> = emptyList(),
    val routines: List<Routine> = emptyList(),
)

data class NovaUiState(
    val status: NovaStatus = NovaStatus.IDLE,
    /** Live, unconfirmed transcript. Displayed but never acted on. */
    val partial: String = "",
    /** Mic level 0f..1f, for the button's pulse. */
    val level: Float = 0f,
    val turns: List<Turn> = emptyList(),
    val message: String? = null,
    /** Set when an action was blocked; the UI offers a button to the right settings screen. */
    val pendingPermission: RequiredPermission? = null,
    /** Non-null while the user is looking at what Raza has stored. */
    val library: LibraryState? = null,
)

class NovaViewModel(private val container: NovaContainer) : ViewModel() {

    private val _state = MutableStateFlow(NovaUiState())
    val state: StateFlow<NovaUiState> = _state.asStateFlow()

    private var listenJob: Job? = null
    private var commandJob: Job? = null

    /**
     * What tapping the orb does, which depends entirely on what Raza is doing.
     *
     * One entry point rather than several buttons: the orb is the only control, and its meaning
     * should follow the state the user can already see.
     */
    fun onOrbTap() {
        when (_state.value.status) {
            NovaStatus.IDLE -> startListening()
            NovaStatus.LISTENING -> stopListening()
            NovaStatus.THINKING, NovaStatus.SPEAKING -> cancel()
        }
    }

    /**
     * Stops whatever is running — including a task started by the wake word or a routine.
     *
     * This matters most for a multi-step task: the planner can run for a couple of minutes,
     * tapping and typing in other apps, and until now there was no way to interrupt it. An
     * agent acting on someone's phone with no stop button is a defect, not a missing feature.
     * Cancelling through the container reaches every entry point's commands, not just the
     * one this screen started.
     */
    fun cancel() {
        container.activeCommands.cancelAll()
        commandJob?.cancel()
        commandJob = null
        container.speaker.stop()
        _state.update { it.copy(status = NovaStatus.IDLE) }
    }

    /**
     * Public so the assist gesture can open the mic directly.
     *
     * Being invoked by a power-button hold means the user has already committed to speaking —
     * making them tap a second button would be pure friction.
     */
    fun startListening() {
        if (listenJob?.isActive == true) return

        listenJob = viewModelScope.launch {
            // Stop mid-sentence playback first, or the recogniser hears Nova's own voice.
            container.speaker.stop()
            _state.update { it.copy(status = NovaStatus.LISTENING, partial = "", message = null) }

            container.speechToText.transcribe().collect { event ->
                when (event) {
                    is SpeechEvent.Listening ->
                        _state.update { it.copy(status = NovaStatus.LISTENING) }

                    is SpeechEvent.Level ->
                        _state.update { it.copy(level = event.db.normaliseLevel()) }

                    is SpeechEvent.Partial ->
                        _state.update { it.copy(partial = event.text) }

                    is SpeechEvent.Final -> {
                        _state.update { it.copy(partial = "", level = 0f) }
                        // Spoken input gets an echo; typed input does not, because the user
                        // is already looking at exactly what they wrote.
                        submit(event.text, echo = true)
                    }

                    is SpeechEvent.Failed -> _state.update {
                        it.copy(
                            status = NovaStatus.IDLE,
                            partial = "",
                            level = 0f,
                            message = event.reason.explain(),
                        )
                    }
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _state.update { it.copy(status = NovaStatus.IDLE, partial = "", level = 0f) }
    }

    /**
     * Runs [utterance] through the agent. Also the entry point for typed commands.
     *
     * [echo] repeats the command back before acting. Worth the extra second for speech, where
     * a misheard command is otherwise only discovered by its consequences.
     */
    fun submit(utterance: String, echo: Boolean = false) {
        if (utterance.isBlank()) return

        // A new command supersedes one still running, rather than the two interleaving taps
        // on whatever app is open.
        commandJob?.cancel()
        commandJob = viewModelScope.launch {
            _state.update { it.copy(status = NovaStatus.THINKING, message = null, pendingPermission = null) }

            // Logged for the same reason the debug receiver logs: without it there is no way
            // to tell "the command never ran" from "it ran and did nothing visible", and that
            // ambiguity cost real time diagnosing the wake word.
            Log.i(TAG, "\"$utterance\"")

            if (echo) container.speaker.speak("You said, $utterance")

            val response = container.runtime.handle(utterance)
            val blocked = response.results.firstNotNullOfOrNull { it as? ActionResult.NeedsPermission }

            _state.update {
                it.copy(
                    status = NovaStatus.SPEAKING,
                    turns = it.turns + Turn(
                        heard = utterance,
                        reply = response.spoken,
                        succeeded = response.succeeded,
                        steps = response.describeSteps(),
                    ),
                    pendingPermission = blocked?.permission,
                )
            }

            Log.i(TAG, "-> ${response.plan.actions} -> ${response.spoken}")

            container.speaker.speak(response.spoken)
            _state.update { it.copy(status = NovaStatus.IDLE) }
            commandJob = null
        }
        // Registered so the stop control can reach it alongside commands from other entry
        // points — the wake word, routines — with one call.
        commandJob?.let { container.activeCommands.track(it) }
    }

    /** Only worth showing when several actions ran — a single command speaks for itself. */
    private fun AgentResponse.describeSteps(): List<String> {
        if (plan.actions.size < 2) return emptyList()

        return plan.actions.mapIndexed { index, action ->
            val outcome = results.getOrNull(index)
            val mark = if (outcome is ActionResult.Success) "✓" else "✗"
            "$mark ${action.describe()}"
        }
    }

    private fun NovaAction.describe(): String = when (this) {
        is NovaAction.OpenApp -> "open $query"
        is NovaAction.TapLabel -> "tap $label"
        is NovaAction.TypeText -> "type \"$text\""
        is NovaAction.ScrollScreen -> "scroll ${direction.name.lowercase()}"
        NovaAction.GoBack -> "back"
        NovaAction.GoHome -> "home"
        else -> this::class.simpleName.orEmpty()
    }

    /** Voices to choose from, loaded on demand. Null while the picker is closed. */
    private val _voices = MutableStateFlow<List<VoiceOption>?>(null)
    val voices: StateFlow<List<VoiceOption>?> = _voices.asStateFlow()

    fun openVoicePicker() {
        viewModelScope.launch { _voices.value = container.speaker.voices() }
    }

    fun closeVoicePicker() {
        _voices.value = null
    }

    /**
     * Switches voice and immediately speaks a sample.
     *
     * Hearing it is the entire point — the engine exposes no gender, so the only reliable test
     * is the user's own ear.
     */
    fun chooseVoice(option: VoiceOption) {
        viewModelScope.launch {
            container.voicePreference.save(option.id)
            container.speaker.useVoice(option.id)
            container.speaker.speak("This is how I sound.")
        }
    }

    fun openLibrary() {
        viewModelScope.launch { refreshLibrary() }
    }

    fun closeLibrary() = _state.update { it.copy(library = null) }

    fun forget(entry: MemoryEntry) {
        viewModelScope.launch {
            container.memory.forget(entry.subject)
            refreshLibrary()
        }
    }

    fun deleteRoutine(routine: Routine) {
        viewModelScope.launch {
            // Cancelled as well as removed. Deleting the row without cancelling the alarm
            // leaves it to fire once more against a routine that no longer exists.
            container.routineScheduler.cancel(routine.id)
            container.routines.remove(routine.id)
            refreshLibrary()
        }
    }

    private suspend fun refreshLibrary() {
        val library = LibraryState(
            memories = container.memory.all(),
            routines = container.routines.all(),
        )
        _state.update { it.copy(library = library) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissPermissionPrompt() = _state.update { it.copy(pendingPermission = null) }

    override fun onCleared() {
        container.speaker.stop()
    }

    /** RMS arrives roughly in -2..12 dB; map that onto 0..1 for the pulse animation. */
    private fun Float.normaliseLevel(): Float = ((this + 2f) / 14f).coerceIn(0f, 1f)

    private fun SpeechError.explain(): String = when (this) {
        SpeechError.NO_MATCH -> "I didn't catch that."
        SpeechError.TIMEOUT -> "I didn't hear anything."
        SpeechError.PERMISSION_DENIED -> "I need microphone access."
        SpeechError.NETWORK -> "Speech recognition needs a network connection right now."
        SpeechError.BUSY -> "The microphone is busy."
        SpeechError.UNAVAILABLE -> "No speech recognition is installed on this device."
        SpeechError.UNKNOWN -> "Something went wrong with the microphone."
    }

    companion object {
        private const val TAG = "NovaCommand"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                NovaViewModel((app as NovaApplication).container)
            }
        }
    }
}
