package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileReviewFeedbackPending
import com.github.claudeagents.core.mobile.MobileReviewNote
import com.github.claudeagents.core.mobile.MobileReviewNoteRequest
import dev.agentdeck.companion.data.NoteEditor
import dev.agentdeck.companion.data.NotesState

/**
 * The desk's "Review notes…" dialog on a phone: every saved note of this conversation, ticked
 * ones attached to the chat's message, and feedback that did not arrive offered again. Notes are
 * added from the diff itself, by long-pressing a line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewNotesSheetView(
    state: NotesState,
    onToggle: (String) -> Unit,
    onHistory: () -> Unit,
    onEdit: (MobileReviewNote) -> Unit,
    onResolve: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAttach: () -> Unit,
    /** The desk's "Reattach review note to the selected range"; null where the machine cannot. */
    onReattach: ((String) -> Unit)?,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("notes-sheet"),
        ) {
            Text("Review notes", style = MaterialTheme.typography.titleMedium)
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = state.showHistory, role = Role.Switch, onValueChange = { onHistory() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show resolved and delivered notes", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.showHistory, onCheckedChange = null)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            state.notes?.pending.orEmpty().forEach { PendingRow(it, enabled = !state.busy) { onRetry(it.batchId) } }
            val shown = state.shown
            if (state.notes != null && shown.isEmpty()) {
                Text(
                    if (state.showHistory) "No saved review notes." else "No open review notes. Long-press a line in a file's diff to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            shown.forEach { note ->
                HorizontalDivider()
                NoteRow(
                    note, checked = note.id in state.selected, enabled = !state.busy,
                    onToggle = { onToggle(note.id) }, onEdit = { onEdit(note) },
                    onResolve = { onResolve(note.id) }, onDelete = { onDelete(note.id) },
                    onReattach = onReattach?.let { { it(note.id) } },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Button(
                    onClick = onAttach,
                    enabled = state.selected.isNotEmpty() && !state.busy,
                    modifier = Modifier.testTag("notes-attach"),
                ) { Text(attachLabel(state.selected.size)) }
            }
        }
    }
}

@Composable
private fun PendingRow(pending: MobileReviewFeedbackPending, enabled: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            pendingText(pending),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, enabled = enabled) { Text("Retry") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteRow(
    note: MobileReviewNote,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onResolve: () -> Unit,
    onDelete: () -> Unit,
    onReattach: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = checked, enabled = enabled && note.open, role = Role.Checkbox, onValueChange = { onToggle() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled && note.open)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(noteLocation(note), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    noteStatus(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            note.quote,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 48.dp),
        )
        Text(note.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 48.dp, top = 4.dp))
        // Four actions on an open note: a FlowRow wraps them at 200% text instead of squeezing Delete.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
            if (note.open) TextButton(onClick = onResolve, enabled = enabled) { Text("Resolve") }
            onReattach?.let { TextButton(onClick = it, enabled = enabled, modifier = Modifier.testTag("note-reattach")) { Text("Reattach") } }
            TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
        }
    }
}

/**
 * Writing one note: the lines it is about above the text, as the desk's "Add review note" dialog
 * shows them. "Next line" widens a new note over the diff's following line on the same side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorSheet(
    editor: NoteEditor,
    onBody: (String) -> Unit,
    onExtend: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("note-editor"),
        ) {
            Text(
                when {
                    editor.reattach -> "Reattach review note"
                    editor.noteId == null -> "Add review note"
                    else -> "Edit review note"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${editor.path} · ${sideLabel(editor.side)} · ${lineLabel(editor.startLine, editor.endLine)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                editor.quote,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (editor.extendable) {
                TextButton(onClick = onExtend, enabled = !editor.saving) { Text("Next line") }
            }
            if (editor.reattach) {
                Text(editor.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            } else OutlinedTextField(
                value = editor.body,
                onValueChange = onBody,
                label = { Text("Review note") },
                enabled = !editor.saving,
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                isError = editor.body.toByteArray(Charsets.UTF_8).size > MobileReviewNoteRequest.BODY_BYTES,
                supportingText = if (editor.body.toByteArray(Charsets.UTF_8).size > MobileReviewNoteRequest.BODY_BYTES) {
                    { Text("Review notes can contain up to 16 KiB of text.") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("note-body"),
            )
            if (editor.saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            editor.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("note-error"))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, enabled = !editor.saving) { Text("Cancel") }
                Button(onClick = onSave, enabled = editor.canSave, modifier = Modifier.testTag("note-save")) {
                    Text(if (editor.reattach) "Reattach" else "Save")
                }
            }
        }
    }
}

fun attachLabel(count: Int): String = when (count) {
    0 -> "Attach to chat"
    1 -> "Attach 1 note to chat"
    else -> "Attach $count notes to chat"
}

fun noteLocation(note: MobileReviewNote): String = "${note.path.substringAfterLast('/')} · ${lineLabel(note.startLine, note.endLine)}"

/** The desk's card status line: outdated first, then the note's lifecycle. */
fun noteStatus(note: MobileReviewNote): String {
    val state = when (note.state) {
        MobileReviewNote.OPEN -> "Open"
        MobileReviewNote.DELIVERED -> "Delivered"
        else -> "Resolved"
    }
    val side = sideLabel(note.side)
    return if (note.outdated) "Saved location is outdated · $side" else "$state · $side"
}

fun pendingText(pending: MobileReviewFeedbackPending): String {
    val notes = if (pending.notes == 1) "1 note" else "${pending.notes} notes"
    return if (pending.state == MobileReviewFeedbackPending.FAILED) {
        "Feedback was not delivered ($notes). Your notes are saved."
    } else {
        "Feedback delivery is unknown ($notes). Your notes are saved."
    }
}

private fun sideLabel(side: String) = if (side == MobileReviewNote.BASELINE) "session start" else "current"

private fun lineLabel(start: Int, end: Int) = if (start == end) "line $start" else "lines $start–$end"
