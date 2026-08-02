package com.nova.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nova.core.agent.help.Capabilities

/**
 * Everything Raza can be asked to do.
 *
 * The spoken answer to "what can you do" names a few areas and stops, because a list read aloud
 * is forgotten by the fourth item. This is the other half of that answer — the same content in
 * the medium where a long list actually works.
 *
 * Phrased as sentences to repeat rather than features to admire. Every example here is covered
 * by a test that fails if Raza stops understanding it, so nothing on this screen is a promise
 * the assistant cannot keep.
 */
@Composable
fun CapabilitiesSheet(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Things you can say") },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        text = {
            LazyColumn {
                items(Capabilities.listed()) { group ->
                    Column {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        group.examples.forEach { example ->
                            Text(
                                text = "“$example”",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        },
    )
}
