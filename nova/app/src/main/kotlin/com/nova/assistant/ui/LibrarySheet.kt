package com.nova.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.core.agent.memory.MemoryEntry
import com.nova.core.agent.routine.Routine
import com.nova.core.agent.routine.RoutineTrigger

/**
 * Everything Raza is holding, with a way to remove any of it.
 *
 * Memory and routines are created by voice and were otherwise write-only. A fact stored from a
 * mishearing, or a reminder set for the wrong hour, could not be seen — and "forget my parking
 * spot" only helps if you already know it went in wrong.
 */
@Composable
fun LibrarySheet(
    library: LibraryState,
    onForget: (MemoryEntry) -> Unit,
    onDeleteRoutine: (Routine) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What I'm holding") },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        text = {
            if (library.memories.isEmpty() && library.routines.isEmpty()) {
                Text(
                    text = "Nothing yet. Try \"remember my parking spot is B2\" " +
                        "or \"every morning at 8 open Spotify\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@AlertDialog
            }

            LazyColumn(
                // Capped so a long list scrolls inside the dialog rather than pushing the
                // Done button off screen.
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (library.memories.isNotEmpty()) {
                    item { SectionHeading("Memory") }
                    items(library.memories, key = { it.subject }) { entry ->
                        Removable(
                            title = entry.subject,
                            detail = entry.detail,
                            onRemove = { onForget(entry) },
                        )
                    }
                }

                if (library.routines.isNotEmpty()) {
                    item {
                        if (library.memories.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                        }
                        SectionHeading("Scheduled")
                    }
                    items(library.routines, key = { it.id }) { routine ->
                        Removable(
                            title = routine.spokenCommand(),
                            detail = routine.scheduleText(),
                            onRemove = { onDeleteRoutine(routine) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Removable(title: String, detail: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                // Named, not just "delete" — a screen reader announcing "delete, delete,
                // delete" down a list tells the user nothing about which one they are on.
                contentDescription = "Forget $title",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "say buy milk" reads as "remind you to buy milk" only when it came from a reminder. */
private fun Routine.spokenCommand(): String {
    val spoken = command.removePrefix("say ")
    return when {
        spoken != command && trigger is RoutineTrigger.OnceAt -> "Remind you to $spoken"
        spoken != command -> "Say $spoken"
        else -> command.replaceFirstChar(Char::uppercase)
    }
}

private fun Routine.scheduleText(): String = when (val trigger = trigger) {
    is RoutineTrigger.Daily -> "Every day at ${trigger.at.spoken()}"
    is RoutineTrigger.OnceAt -> "At ${trigger.at.spoken()}"
    is RoutineTrigger.BatteryBelow -> "When battery drops below ${trigger.percent}%"
    RoutineTrigger.PowerConnected -> "When you plug in"
    RoutineTrigger.PowerDisconnected -> "When you unplug"
}
