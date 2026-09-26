package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.OpenMemoryFile

/**
 * The text editor of a machine file the phone edits — a memory file, a skill's `SKILL.md` — with its Save, Discard, read-only
 * notice and conflict card. One composable for both so the two dialogs cannot drift apart; [tag] prefixes every test tag
 * (`memory-field`, `skill-file-save`, …). [readOnly] is the sentence a phone without the grant reads instead of a Save that fails, and
 * [leading] adds a button before the status (memory's Delete) inside the same row, shown only while the phone may save.
 */
@Composable
internal fun FileEditor(
    open: OpenMemoryFile,
    busy: Boolean,
    tag: String,
    readOnly: String,
    onEdit: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepMine: () -> Unit,
    onLoadTheirs: () -> Unit,
    leading: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!open.writable) FileNotice(tag, readOnly)
        open.conflict?.let { FileConflict(tag, onLoadTheirs, onKeepMine) }
        open.error?.let { FileNotice(tag, it, error = true) }
        OutlinedTextField(
            value = open.text,
            onValueChange = onEdit,
            readOnly = !open.writable,
            label = { Text(if (open.existed) "Text" else "Text (a first save creates this file)") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("$tag-field"),
        )
        if (open.writable) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                leading()
                Text(
                    when {
                        open.saved -> "Saved on the machine."
                        open.dirty -> "Unsaved changes are kept on this phone."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("$tag-status"),
                )
                TextButton(onClick = onDiscard, enabled = open.dirty && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-discard")) { Text("Discard") }
                Button(
                    onClick = onSave,
                    enabled = open.dirty && !busy && open.conflict == null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-save"),
                ) { Text(if (busy) "Saving…" else "Save") }
            }
        }
    }
}

@Composable
private fun FileConflict(tag: String, onLoadTheirs: () -> Unit, onKeepMine: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("$tag-conflict")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "This file changed on the machine after you opened it. Your text is kept below; nothing was overwritten.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLoadTheirs, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-load-theirs")) { Text("Load the machine's") }
                Button(onClick = onKeepMine, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-keep-mine")) { Text("Save mine over it") }
            }
        }
    }
}

@Composable
internal fun FileNotice(tag: String, text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("$tag-notice"),
    )
}
