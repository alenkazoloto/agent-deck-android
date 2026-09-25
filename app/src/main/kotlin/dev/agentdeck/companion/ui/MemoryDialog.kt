package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileMemoryEntry
import com.github.claudeagents.core.mobile.MobileMemoryProject
import dev.agentdeck.companion.data.MemorySheet
import dev.agentdeck.companion.data.OpenMemoryFile

/** Everything the dialog can ask of its flow; one bundle so a test drives it without a view model. */
internal class MemoryActions(
    val onChooseProject: (MobileMemoryProject) -> Unit,
    val onOpenFile: (String) -> Unit,
    val onEdit: (String) -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onKeepMine: () -> Unit,
    val onLoadTheirs: () -> Unit,
    val onDelete: () -> Unit,
    val onBack: () -> Unit,
    val onDismiss: () -> Unit,
    val hasDraft: (String) -> Boolean,
)

/**
 * Resources › Memory and instructions: the desk Memory tab's files for an open project — the agent's
 * notes, `CLAUDE.md` and its siblings, the rules — read and edited as text (`/v1/memory`).
 *
 * Three steps on one full-screen dialog: the machine's open projects (skipped when there is one), the
 * project's files, and the file's text. Back steps out one level and never discards typing — the edit
 * is kept until the machine takes it or the reader presses Discard. A phone the owner has not allowed
 * to save shows the file read-only and says how to change that, rather than a Save that fails.
 *
 * An agent note the machine marks deletable also offers Delete — the desk's "Delete this memory" —
 * behind a confirmation that says what goes and that the phone cannot bring it back.
 */
@Composable
internal fun MemoryDialog(sheet: MemorySheet, actions: MemoryActions) {
    Dialog(onDismissRequest = actions.onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("memory-dialog"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Header(sheet, actions)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val open = sheet.open
                when {
                    open != null -> Editor(sheet, open, actions)
                    sheet.project != null -> Files(sheet, actions)
                    else -> Projects(sheet, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(sheet: MemorySheet, actions: MemoryActions) {
    val title = sheet.open?.label ?: sheet.project?.name ?: "Memory and instructions"
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = actions.onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Text("Back", modifier = Modifier.padding(start = 8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp))
    }
}

@Composable
private fun Projects(sheet: MemorySheet, actions: MemoryActions) {
    val projects = sheet.projects
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        when {
            projects == null && sheet.error == null -> Notice("Reading this machine's projects…")
            projects == null -> Unit
            projects.isEmpty() -> Notice("No project is open in the IDE on this machine. Open one there to read its memory.")
            else -> {
                Detail("Choose a project", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                projects.forEach { project ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { actions.onChooseProject(project) }
                            .padding(horizontal = 20.dp, vertical = 8.dp).testTag("memory-project-row"),
                    ) {
                        Text(project.name, style = MaterialTheme.typography.bodyLarge)
                        Detail(project.path)
                    }
                }
            }
        }
        sheet.error?.let { Notice(it, error = true) }
    }
}

@Composable
private fun Files(sheet: MemorySheet, actions: MemoryActions) {
    val entries = sheet.entries
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        when {
            entries == null && sheet.error == null -> Notice("Reading this project's memory files…")
            entries == null -> Unit
            entries.unavailable != null -> Notice(entries.unavailable.orEmpty())
            else -> {
                if (!entries.writable) Notice(READ_ONLY)
                entries.entries.groupBy { it.group }.forEach { (group, files) ->
                    Text(
                        group,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                    files.forEach { FileRow(it, actions.hasDraft(it.id)) { actions.onOpenFile(it.id) } }
                }
            }
        }
        sheet.error?.let { Notice(it, error = true) }
    }
}

@Composable
private fun FileRow(entry: MobileMemoryEntry, unsaved: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp).testTag("memory-entry-row")) {
        Text(entry.label, style = MaterialTheme.typography.bodyLarge)
        Detail(fileStatus(entry, unsaved))
        entry.description?.let { Detail(it) }
    }
}

/** "Instructions · Project · Not written yet · Unsaved changes" — every state a word, never a colour. */
internal fun fileStatus(entry: MobileMemoryEntry, unsaved: Boolean): String = listOfNotNull(
    // The group header already says it; repeating "Agent memory" under every note is noise.
    entry.source.takeIf { it != entry.group },
    "Not written yet".takeIf { !entry.hasContent },
    "Only for matching paths".takeIf { entry.conditional },
    "Unsaved changes".takeIf { unsaved },
).joinToString(" · ")

@Composable
private fun Editor(sheet: MemorySheet, open: OpenMemoryFile, actions: MemoryActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!open.writable) Notice(READ_ONLY)
        open.conflict?.let { Conflict(actions) }
        open.error?.let { Notice(it, error = true) }
        OutlinedTextField(
            value = open.text,
            onValueChange = actions.onEdit,
            readOnly = !open.writable,
            label = { Text(if (open.existed) "Text" else "Text (a first save creates this file)") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("memory-field"),
        )
        if (open.writable) {
            var confirming by rememberSaveable(open.id) { mutableStateOf(false) }
            if (confirming) DeleteConfirm(open, onConfirm = { confirming = false; actions.onDelete() }, onCancel = { confirming = false })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (open.deletable) {
                    TextButton(
                        onClick = { confirming = true },
                        enabled = !sheet.busy && open.conflict == null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("memory-delete"),
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Text(
                    when {
                        open.saved -> "Saved on the machine."
                        open.dirty -> "Unsaved changes are kept on this phone."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("memory-status"),
                )
                TextButton(onClick = actions.onDiscard, enabled = open.dirty && !sheet.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-discard")) { Text("Discard") }
                Button(
                    onClick = actions.onSave,
                    enabled = open.dirty && !sheet.busy && open.conflict == null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("memory-save"),
                ) { Text(if (sheet.busy) "Saving…" else "Save") }
            }
        }
    }
}

@Composable
private fun DeleteConfirm(open: OpenMemoryFile, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete ${open.label}?") },
        text = {
            Text(
                "The machine removes this note and its line in the memory index, and the agent stops reading it. " +
                    "This phone cannot bring it back." + if (open.dirty) " Your unsaved changes to it go too." else "",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-delete-confirm")) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-delete-cancel")) { Text("Cancel") } },
    )
}

@Composable
private fun Conflict(actions: MemoryActions) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("memory-conflict")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "This file changed on the machine after you opened it. Your text is kept below; nothing was overwritten.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onLoadTheirs, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-load-theirs")) { Text("Load the machine's") }
                Button(onClick = actions.onKeepMine, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-keep-mine")) { Text("Save mine over it") }
            }
        }
    }
}

@Composable
private fun Detail(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("memory-notice"),
    )
}

/** Where the owner turns saving on for this phone; the machine's refusal names the same place. */
internal const val READ_ONLY =
    "Read only: saving memory from this phone is off. Turn it on for this phone in the IDE, under Settings › Connections › Mobile › Devices."
