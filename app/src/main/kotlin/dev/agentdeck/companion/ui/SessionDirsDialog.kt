package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.agentdeck.companion.data.SessionDirsSheet

/**
 * A chat's additional working directories, the desk folder button's list (`/v1/session-dirs`): each
 * directory with a remove button, and a typed path to add. The path is text because a phone cannot
 * browse this machine's disk; the machine's own sentence answers a path it will not take, and the
 * list and the typed text stay as they were.
 */
@Composable
internal fun SessionDirsDialog(
    sheet: SessionDirsSheet,
    onDraft: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("session-dirs-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("Working directories", style = MaterialTheme.typography.headlineSmall)
                    if (sheet.title.isNotBlank()) {
                        Text(
                            sheet.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // One scrolling body under the fixed button: at 200% text the list outgrows the dialog.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val dirs = sheet.dirs
                    when {
                        dirs == null && sheet.error == null -> Notice("Reading this conversation…")
                        dirs == null -> Unit
                        dirs.isEmpty() -> Notice("No additional working directories.")
                        else -> dirs.forEach { dir -> DirRow(dir, enabled = !sheet.busy, onRemove = { onRemove(dir) }) }
                    }
                    if (dirs != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sheet.draft,
                                onValueChange = onDraft,
                                label = { Text("Directory path") },
                                singleLine = true,
                                isError = sheet.refused != null,
                                supportingText = sheet.refused?.let { { Text(it, modifier = Modifier.testTag("session-dirs-refused")) } },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                                modifier = Modifier.weight(1f).testTag("session-dirs-field"),
                            )
                            Button(
                                onClick = onAdd,
                                enabled = sheet.draft.isNotBlank() && !sheet.busy,
                                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp).testTag("session-dirs-add"),
                            ) { Text("Add") }
                        }
                    }
                    sheet.error?.let { Notice(it, error = true) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun DirRow(dir: String, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("session-dirs-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dir, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        DeckIconButton("Remove $dir", Icons.Filled.Close, onRemove, enabled = enabled)
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("session-dirs-notice"),
    )
}
