package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobilePromptLibrary
import kotlinx.coroutines.launch

/**
 * The desk's prompt library as this conversation's composer reaches it: `/prompts` opens the list and
 * `/saveprompt` the dialog that keeps a new row. Handed down as one object so a machine that does not
 * advertise `prompt-library` is a null, and neither command is offered.
 */
class PromptLibraryActions(
    /** The rows, newest first; null is a machine that did not answer, not an empty library. */
    val load: suspend () -> List<MobilePromptLibrary.Entry>?,
    /** Keeps one; null on success, else the sentence to show under the fields. */
    val save: suspend (name: String, text: String) -> String?,
)

/** More rows than this and a name filter earns its place; fewer scan faster than they filter. */
private const val FILTER_FROM = 6

/**
 * `/prompts [name]`: the saved prompts, a tap putting one in the composer. Read each time it opens, so a
 * prompt just saved at the desk is already there. [query] is what was typed after the command.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedPromptsSheet(
    query: String,
    onLoad: suspend () -> List<MobilePromptLibrary.Entry>?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<MobilePromptLibrary.Entry>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(query) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        entries = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Saved prompts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            val all = entries
            // Shown for a long list, or while a typed name is filtering: hiding it then would leave the list cut with no way to widen it.
            if (all != null && (all.size >= FILTER_FROM || filter.isNotBlank())) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            when {
                loading -> PromptNote("Reading saved prompts…")
                all == null -> PromptNote("The machine did not answer. Close this and try again.")
                all.isEmpty() -> PromptNote("No saved prompts yet. Type /saveprompt and a name, then the prompt under it.")
                else -> {
                    val shown = MobilePromptLibrary.matching(all, filter)
                    if (shown.isEmpty()) {
                        PromptNote("No saved prompt is named like that.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 480.dp)) {
                            // Not keyed by name: a hand-edited settings file can hold two rows of one name, and a repeated key crashes the list.
                            itemsIndexed(shown) { index, row ->
                                if (index > 0) HorizontalDivider()
                                Column(Modifier.fillMaxWidth().clickable { onPick(row.text) }.padding(vertical = 12.dp)) {
                                    Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        MobilePromptLibrary.preview(row.text),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * `/saveprompt`: a name and the prompt, kept in the desk's library. Saving a name already there replaces
 * that prompt, as the desk's does, and the dialog says so before it happens. A refusal stays under the
 * fields with the dialog open, so nothing typed is lost to a machine that was unreachable.
 */
@Composable
internal fun SavePromptDialog(
    name: String,
    text: String,
    onLoad: suspend () -> List<MobilePromptLibrary.Entry>?,
    onSave: suspend (name: String, text: String) -> String?,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Saveable: the body is typed here after a bare `/saveprompt` emptied the composer, and a rotation must not eat it.
    var nameField by rememberSaveable { mutableStateOf(name) }
    var textField by rememberSaveable { mutableStateOf(text) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // The names already kept, to say a save replaces one; a machine that does not answer just leaves the warning off.
    var kept by remember { mutableStateOf<Set<String>>(emptySet()) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) { kept = load().orEmpty().mapTo(HashSet()) { it.name.lowercase() } }
    val scope = rememberCoroutineScope()
    val refusal = MobilePromptLibrary.refusal(nameField, textField)
    // Only what the reader has typed is faulted: an empty name is a form not filled in yet, not an error.
    val complaint = failure ?: refusal?.takeIf { nameField.isNotBlank() && textField.isNotBlank() }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Text("Save prompt", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it; failure = null },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = textField,
                    onValueChange = { textField = it; failure = null },
                    label = { Text("Prompt") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                val note = complaint ?: "Replaces the saved prompt of that name.".takeIf { nameField.trim().lowercase() in kept }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (complaint != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val error = onSave(nameField.trim(), textField.trim())
                                saving = false
                                if (error == null) onSaved() else failure = error
                            }
                        },
                        enabled = refusal == null && !saving,
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    }
}

@Composable
private fun PromptNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}
