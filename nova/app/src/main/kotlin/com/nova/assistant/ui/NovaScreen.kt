package com.nova.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.core.agent.RequiredPermission

@Composable
fun NovaScreen(
    state: NovaUiState,
    micGranted: Boolean,
    accessibilityEnabled: Boolean,
    alwaysListening: Boolean,
    /** Where multi-step planning is running: on-device model, API key, or nothing yet. */
    plannerSummary: String,
    hasApiKey: Boolean,
    onMicTap: () -> Unit,
    onSubmit: (String) -> Unit,
    onRequestMic: () -> Unit,
    onOpenSettingsFor: (RequiredPermission) -> Unit,
    onDismissPermissionPrompt: () -> Unit,
    onAlwaysListeningChange: (Boolean) -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    voices: List<com.nova.core.speech.VoiceOption>?,
    selectedVoiceId: String?,
    onOpenVoicePicker: () -> Unit,
    onCloseVoicePicker: () -> Unit,
    onChooseVoice: (com.nova.core.speech.VoiceOption) -> Unit,
    onOpenLibrary: () -> Unit,
    onCloseLibrary: () -> Unit,
    onForget: (com.nova.core.agent.memory.MemoryEntry) -> Unit,
    onDeleteRoutine: (com.nova.core.agent.routine.Routine) -> Unit,
    modifier: Modifier = Modifier,
) {
    voices?.let {
        VoicePicker(
            voices = it,
            selectedId = selectedVoiceId,
            onChoose = onChooseVoice,
            onClose = onCloseVoicePicker,
        )
    }

    state.library?.let { library ->
        LibrarySheet(
            library = library,
            onForget = onForget,
            onDeleteRoutine = onDeleteRoutine,
            onClose = onCloseLibrary,
        )
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(20.dp)) {

            Header(state.status, onOpenLibrary)

            Spacer(Modifier.height(12.dp))

            if (!micGranted) {
                ActionCard(
                    text = "Raza needs microphone access to hear you.",
                    button = "Grant",
                    onClick = onRequestMic,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Offered up front rather than only after a command fails: without it, half of
            // what Nova can do is invisible, and the user has no reason to go looking.
            if (micGranted && !accessibilityEnabled && state.pendingPermission == null) {
                ActionCard(
                    text = "Turn on Raza's accessibility service to control other apps — tap, scroll, type, lock.",
                    button = "Enable",
                    onClick = { onOpenSettingsFor(RequiredPermission.ACCESSIBILITY_SERVICE) },
                )
                Spacer(Modifier.height(8.dp))
            }

            state.pendingPermission?.let { permission ->
                ActionCard(
                    text = permission.explain(),
                    button = "Open settings",
                    onClick = {
                        onOpenSettingsFor(permission)
                        onDismissPermissionPrompt()
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            // Open by default only while something still needs doing. These rows matter once,
            // during setup, and then never again — leaving them expanded pushes the thing the
            // user actually reaches for to the bottom of the screen forever.
            var setupOpen by rememberSaveable { mutableStateOf(!accessibilityEnabled || !micGranted) }

            SetupSection(
                open = setupOpen,
                onToggle = { setupOpen = !setupOpen },
                alwaysListening = alwaysListening,
                micGranted = micGranted,
                plannerSummary = plannerSummary,
                hasApiKey = hasApiKey,
                onAlwaysListeningChange = onAlwaysListeningChange,
                onOpenAssistantSettings = onOpenAssistantSettings,
                onSaveApiKey = onSaveApiKey,
                onClearApiKey = onClearApiKey,
                onOpenVoicePicker = onOpenVoicePicker,
            )

            Spacer(Modifier.height(12.dp))

            Transcript(state, modifier = Modifier.weight(1f))

            state.message?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            CommandInput(onSubmit)

            Spacer(Modifier.height(16.dp))

            VoiceOrb(
                mode = state.status.toOrbMode(),
                amplitude = state.level,
                onTap = { if (micGranted) onMicTap() else onRequestMic() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The setup rows, behind a single line when there is nothing to do.
 *
 * Collapsed it reads as one word; expanded it is the same three controls as before. Nothing is
 * hidden that the user still needs — the section opens itself whenever a permission is missing.
 */
@Composable
private fun SetupSection(
    open: Boolean,
    onToggle: () -> Unit,
    alwaysListening: Boolean,
    micGranted: Boolean,
    plannerSummary: String,
    hasApiKey: Boolean,
    onAlwaysListeningChange: (Boolean) -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onOpenVoicePicker: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
        ) {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                // Says what is on rather than just "expand", so the collapsed line still
                // carries the state it is hiding.
                text = if (open) "Hide" else summaryOf(alwaysListening, hasApiKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (open) {
            Spacer(Modifier.height(8.dp))
            AssistGestureRow(onOpenAssistantSettings)

            Spacer(Modifier.height(8.dp))
            AlwaysListeningRow(alwaysListening, micGranted, onAlwaysListeningChange)

            Spacer(Modifier.height(8.dp))
            PlannerRow(
                summary = plannerSummary,
                hasKey = hasApiKey,
                onSave = onSaveApiKey,
                onClear = onClearApiKey,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Voice", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Android doesn't say which voices are male, so pick by ear.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenVoicePicker) { Text("Choose") }
            }
        }
    }
}

private fun summaryOf(alwaysListening: Boolean, hasApiKey: Boolean): String {
    val on = buildList {
        if (alwaysListening) add("always listening")
        if (hasApiKey) add("planner")
    }
    return if (on.isEmpty()) "Show" else on.joinToString(", ")
}

private fun NovaStatus.toOrbMode(): OrbMode = when (this) {
    NovaStatus.IDLE -> OrbMode.IDLE
    NovaStatus.LISTENING -> OrbMode.LISTENING
    NovaStatus.THINKING -> OrbMode.THINKING
    NovaStatus.SPEAKING -> OrbMode.SPEAKING
}

@Composable
private fun Header(status: NovaStatus, onOpenLibrary: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Raza",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when (status) {
                    NovaStatus.IDLE -> "Ready"
                    NovaStatus.LISTENING -> "Listening…"
                    NovaStatus.THINKING -> "Working on it…"
                    NovaStatus.SPEAKING -> "Speaking"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenLibrary) { Text("What I know") }
    }
}

/**
 * The hands-free route that costs nothing.
 *
 * Offered above the always-listening switch on purpose: most people reaching for "wake on my
 * voice" actually want "reach Nova without touching the screen", and the gesture does that with
 * no microphone held open at all.
 */
@Composable
private fun AssistGestureRow(onOpenAssistantSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Assistant gesture", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Set Raza as your assistant app — hold power or swipe a corner to talk. " +
                    "No battery cost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenAssistantSettings) { Text("Set up") }
    }
}

@Composable
private fun AlwaysListeningRow(
    enabled: Boolean,
    micGranted: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Always listening", style = MaterialTheme.typography.bodyLarge)
            Text(
                // Still honest: the gate cuts the idle cost, but a room with talking in it
                // still wakes the recogniser repeatedly.
                text = "Wake on \"Raza\". Only listens properly once it hears a voice, " +
                    "but still costs battery in a noisy room.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange, enabled = micGranted)
    }
}

/**
 * Lets the key be replaced without a rebuild.
 *
 * Rotating a leaked key should take seconds, and the build-time key is baked into the APK —
 * so without this, replacing it means editing local.properties, rebuilding and reinstalling.
 */
@Composable
private fun PlannerRow(
    summary: String,
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Multi-step tasks", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { editing = true }) {
            Text(if (hasKey) "Change" else "Set key")
        }
    }

    if (editing) {
        ApiKeyDialog(
            hasKey = hasKey,
            onDismiss = { editing = false },
            onSave = {
                onSave(it)
                editing = false
            },
            onClear = {
                onClear()
                editing = false
            },
        )
    }
}

@Composable
private fun ApiKeyDialog(
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenAI API key") },
        text = {
            Column {
                Text(
                    text = "Used only for commands the built-in rules can't handle. " +
                        "Stored on this device, in Raza's private storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(entry) }, enabled = entry.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (hasKey) {
                    TextButton(onClick = onClear) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Transcript(state: NovaUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (state.turns.isEmpty() && state.partial.isEmpty()) {
            EmptyHint(Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.turns) { turn -> TurnRow(turn) }
            }
        }

        if (state.partial.isNotEmpty()) {
            Text(
                text = state.partial,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TurnRow(turn: Turn) {
    Column {
        Text(
            text = turn.heard,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = turn.reply,
            style = MaterialTheme.typography.bodyMedium,
            color = if (turn.succeeded) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        turn.steps.forEach { step ->
            Text(
                text = step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Try saying",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "Open YouTube",
            "Turn on the flashlight",
            "Set volume to 40 percent",
            "Increase brightness",
        ).forEach {
            Text(
                text = "“$it”",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommandInput(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Type a command") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                onSubmit(text)
                text = ""
            },
            enabled = text.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command")
        }
    }
}

@Composable
private fun ActionCard(text: String, button: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

private fun RequiredPermission.explain(): String = when (this) {
    RequiredPermission.WRITE_SYSTEM_SETTINGS -> "Allow Raza to modify system settings to control brightness."
    RequiredPermission.DO_NOT_DISTURB -> "Allow Do Not Disturb access so Raza can silence the ringer."
    RequiredPermission.RECORD_AUDIO -> "Raza needs microphone access."
    RequiredPermission.CAMERA -> "Raza needs camera access."
    RequiredPermission.ACCESSIBILITY_SERVICE -> "Turn on Raza's accessibility service to control other apps."
    RequiredPermission.NOTIFICATION_LISTENER -> "Allow notification access so Raza can read notifications."
    RequiredPermission.USAGE_STATS -> "Allow usage access so Raza can tell which app is open."
    RequiredPermission.DEVICE_ADMIN -> "Raza needs device admin rights for that."
    RequiredPermission.READ_CONTACTS -> "Allow contacts access so Raza can find who you mean."
    RequiredPermission.CALL_PHONE -> "Allow Raza to place calls."
    RequiredPermission.SEND_SMS -> "Allow Raza to send messages."
}
