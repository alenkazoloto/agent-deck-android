package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

/** Why [raw] cannot name a new folder, or null when it can — the desk's two sentences. */
internal fun folderNameProblem(raw: String, folders: List<MobileFolder>): String? {
    val name = normalizeFolderName(raw)
    if (name.isEmpty()) return "A folder needs a name."
    return if (folders.any { it.name.equals(name, ignoreCase = true) }) "A folder called \"$name\" already exists." else null
}

/** `SessionFolders.NAME_MAX_LENGTH`; `core/sessions` is not shared with the phone. */
private const val FOLDER_NAME_MAX = 60
