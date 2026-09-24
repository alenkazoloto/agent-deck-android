package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileFolder
import com.github.claudeagents.core.mobile.MobileFolderActionRequest

/**
 * Files one chat into the desk's Sessions folders: "No folder", each folder, or a new one named
 * here. A pick is the whole gesture — the dialog closes on it, as a single-choice list does.
 *
 * The name rules are the desk's (`SessionFolders.nameProblem`): the machine refuses a clash
 * anyway, but a Create button that is live for a name already taken would fail every time.
 * Sized by hand for [RenameDialog]'s Robolectric reason.
 */
@Composable
internal fun FolderDialog(
    currentId: String?,
    folders: List<MobileFolder>,
    onDismiss: () -> Unit,
    /** The chosen folder's id; blank takes the chat out of its folder. */
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val problem = folderNameProblem(name, folders)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Text("Move to folder", style = MaterialTheme.typography.headlineSmall)
                Column(
                    Modifier.padding(top = 12.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()).selectableGroup(),
                ) {
                    FolderChoice("No folder", selected = currentId == null) { onPick("") }
                    folders.forEach { folder ->
                        FolderChoice(folderLabel(folder), selected = folder.id == currentId) { onPick(folder.id) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= FOLDER_NAME_MAX) name = it },
                    label = { Text("New folder") },
                    singleLine = true,
                    isError = name.isNotBlank() && problem != null,
                    supportingText = problem?.takeIf { name.isNotBlank() }?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (problem == null) onCreate(name) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onCreate(name) }, enabled = problem == null) { Text("Create and move") }
                }
            }
        }
    }
}

@Composable
private fun FolderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** A done folder reads as the desk's combo reads it: finished work, still a place to file into. */
internal fun folderLabel(folder: MobileFolder): String = if (folder.done) "${folder.name} (done)" else folder.name

/** `SessionFolders.normalizeName`'s rule, so the clash check compares what the machine would store. */
internal fun normalizeFolderName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(FOLDER_NAME_MAX)

/**
 * Why [raw] cannot name a folder, or null when it can — the desk's two sentences. [exceptId] is the
 * folder being renamed, which does not clash with itself.
 */
internal fun folderNameProblem(raw: String, folders: List<MobileFolder>, exceptId: String? = null): String? {
    val name = normalizeFolderName(raw)
    if (name.isEmpty()) return "A folder needs a name."
    return if (folders.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) "A folder called \"$name\" already exists." else null
}

/**
 * The desk's folder menu (Rename…, Note…, Mark done/Reopen, Delete…) as one form over [folder].
 * Save sends only the fields changed here, so an edit made at the desk meanwhile to another field
 * survives. Delete asks first, naming how many chats go back to no folder — the desk's question,
 * and neither side can undo it.
 */
@Composable
internal fun FolderEditDialog(
    folder: MobileFolder,
    folders: List<MobileFolder>,
    onDismiss: () -> Unit,
    onSave: (MobileFolderActionRequest) -> Unit,
) {
    // The folder as the form opened: a desk edit arriving in a later frame is not the reader's pick,
    // and diffing against the live folder would send the stale field back over it.
    val opened = remember(folder.id) { folder }
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    var note by remember(folder.id) { mutableStateOf(folder.note) }
    var done by remember(folder.id) { mutableStateOf(folder.done) }
    var confirmingDelete by remember(folder.id) { mutableStateOf(false) }
    val problem = folderNameProblem(name, folders, exceptId = folder.id)
    val newName = normalizeFolderName(name).takeIf { it != opened.name }
    val newNote = note.trim().takeIf { it != opened.note }
    val newDone = done.takeIf { it != opened.done }
    val changed = newName != null || newNote != null || newDone != null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp).verticalScroll(rememberScrollState())) {
                if (confirmingDelete) {
                    Text("Delete folder?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Delete the folder \u201C${folder.name}\u201D? " + when (folder.count) {
                            0 -> "It holds no chats."
                            1 -> "Its chat stays in the list, in no folder."
                            else -> "Its ${folder.count} chats stay in the list, in no folder."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                        TextButton(
                            onClick = { onSave(MobileFolderActionRequest(folder.id, MobileFolderActionRequest.DELETE)) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                    }
                    return@Column
                }
                Text("Edit folder", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= FOLDER_NAME_MAX) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= FOLDER_NOTE_MAX) note = it },
                    label = { Text("Note") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(min = 48.dp)
                        .toggleable(value = done, role = Role.Checkbox, onValueChange = { done = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = done, onCheckedChange = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Done", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Sorts below the other folders; its chats stay listed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { confirmingDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete\u2026") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onSave(MobileFolderActionRequest(folder.id, MobileFolderActionRequest.EDIT, newName, newNote, newDone)) },
                        enabled = changed && problem == null,
                    ) { Text("Save") }
                }
            }
        }
    }
}

/** `SessionFolders.NAME_MAX_LENGTH`; `core/sessions` is not shared with the phone. */
private const val FOLDER_NAME_MAX = 60

/** `SessionFolders.NOTE_MAX_LENGTH`. */
private const val FOLDER_NOTE_MAX = 500
