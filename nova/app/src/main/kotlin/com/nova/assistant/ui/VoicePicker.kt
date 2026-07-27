package com.nova.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.core.speech.VoiceOption

/**
 * Pick a voice by hearing it.
 *
 * Android's TTS API exposes no gender, and Google's on-device voices are named with opaque
 * codes like `en-in-x-enc`. Every automatic guess is exactly that — and two of them were wrong
 * on this device. Tapping a row speaks a sample immediately, so the user's ear decides instead
 * of a lookup table.
 */
@Composable
fun VoicePicker(
    voices: List<VoiceOption>,
    selectedId: String?,
    onChoose: (VoiceOption) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Voice") },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        text = {
            if (voices.isEmpty()) {
                Text(
                    "No voices are installed for your language yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@AlertDialog
            }

            Column {
                Text(
                    text = "Tap one to hear it. The one you pick is remembered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(voices, key = { it.id }) { voice ->
                        VoiceRow(
                            voice = voice,
                            selected = voice.id == selectedId,
                            onClick = { onChoose(voice) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun VoiceRow(voice: VoiceOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = voice.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                // "Probably male" rather than "male" — the hint comes from a lookup table
                // that has already been wrong, and overstating it would be worse than silence.
                text = listOfNotNull(
                    if (voice.offline) "offline" else "needs network",
                    if (voice.likelyMale) "probably male" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text("✓", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
