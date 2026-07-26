package com.nova.assistant.ui

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
import com.nova.core.speech.SpeechError
import com.nova.core.speech.SpeechEvent
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
)

class NovaViewModel(private val container: NovaContainer) : ViewModel() {

    private val _state = MutableStateFlow(NovaUiState())
    val state: StateFlow<NovaUiState> = _state.asStateFlow()

    private var listenJob: Job? = null

    fun toggleListening() {
        if (_state.value.status == NovaStatus.LISTENING) stopListening() else startListening()
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

        viewModelScope.launch {
            _state.update { it.copy(status = NovaStatus.THINKING, message = null, pendingPermission = null) }

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

            container.speaker.speak(response.spoken)
            _state.update { it.copy(status = NovaStatus.IDLE) }
        }
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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                NovaViewModel((app as NovaApplication).container)
            }
        }
    }
}
