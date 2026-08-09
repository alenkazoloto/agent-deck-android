package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledRow
import dev.agentdeck.companion.data.ScheduleWhen

/**
 * What is queued on the machine, and — for the first time — a way to add to it.
 *
 * The screen had none of the fleet's manners: no pull-to-refresh, **Cancel** and **Cancel all**
 * firing straight through with nothing between the thumb and a lost prompt, and rows that named
 * neither project nor branch, so two prompts from different repos were the same row twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    rows: List<MobileScheduledRow>,
    loading: Boolean,
    canCreate: Boolean,
    projects: List<String>,
    draft: String,
    onDraft: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: (projectPath: String, dueAtMs: Long) -> Unit,
    onCommand: (action: String, ids: List<String>, announce: String?) -> Unit,
) {
    var cancelling by remember { mutableStateOf<List<MobileScheduledRow>?>(null) }
    var composing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            // Only where the machine says it will honour a due time. An older plugin ignores
            // the field and runs the prompt immediately, which is not what "schedule" means.
            if (canCreate) {
                OutlinedButton(onClick = { composing = true }) { Text("Schedule a prompt") }
            }
            if (rows.isNotEmpty()) {
                // The ids are the ones on screen. A "cancel everything" request would race
                // the list the user is looking at and kill a row that arrived in between.
                OutlinedButton(onClick = { cancelling = rows }) { Text("Cancel all ${rows.size}") }
            }
        }

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (rows.isEmpty() && !loading) {
                    item { Text("Nothing is scheduled on this machine.", Modifier.padding(16.dp)) }
                }
                items(rows, key = { it.id }) { row ->
                    ScheduledRow(row, onCommand) { cancelling = listOf(row) }
                }
            }
        }
    }

    cancelling?.let { doomed ->
        ConfirmCancel(doomed, onDismiss = { cancelling = null }) {
            cancelling = null
            onCommand(
                MobileScheduledCommand.CANCEL,
                doomed.map { it.id },
                if (doomed.size == 1) "Cancelled 1 prompt" else "Cancelled ${doomed.size} prompts",
            )
        }
    }

    if (composing) {
        ScheduleDialog(
            projects = projects,
            draft = draft,
            onDraft = onDraft,
            onDismiss = { composing = false },
            onCreate = { project, dueAtMs ->
                composing = false
                onCreate(project, dueAtMs)
            },
        )
    }
}

/**
 * The gate in front of the one irreversible act here.
 *
 * There is no undo for a cancel: the row is gone from the machine's queue and the plugin mints
 * no way to put it back. So the prompt itself is quoted in the dialog — the thing the user is
 * about to lose is the text they wrote, and a dialog that says "Cancel 1 prompt?" without
 * showing which one is asking them to confirm from memory.
 */
@Composable
private fun ConfirmCancel(
    rows: List<MobileScheduledRow>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rows.size == 1) "Cancel this prompt?" else "Cancel ${rows.size} prompts?") },
        text = {
            Column {
                Text("Cancelling removes them from the machine's queue. This cannot be undone.")
                rows.take(3).forEach { row ->
                    Text(
                        "· ${row.prompt.ifBlank { "(empty prompt)" }}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (rows.size > 3) {
                    Text(
                        "and ${rows.size - 3} more",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Cancel them") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep them") } },
    )
}

/**
 * The thing you remember while away from the desk.
 *
 * Relative choices rather than a date-and-time picker: the prompts a user queues from a phone
 * are "when I am back at it", not "at 14:37 on the 9th", and a wheel picker on a phone to
 * express "tomorrow morning" is four gestures for a decision that is one.
 */
@Composable
private fun ScheduleDialog(
    projects: List<String>,
    draft: String,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, Long) -> Unit,
) {
    var project by remember { mutableStateOf(projects.firstOrNull().orEmpty()) }
    var due by remember { mutableStateOf(ScheduleWhen.entries.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule a prompt") },
        text = {
            Column {
                if (projects.isEmpty()) {
                    Text("No project is open on this machine, so there is nowhere to run a prompt.")
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraft,
                        placeholder = { Text("What should the agent do?") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp),
                    )
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (projects.size > 1) {
                            Selector(
                                options = projects.map { SelectorOption(it.substringAfterLast('/'), it) },
                                selected = project,
                                onSelect = { project = it },
                            )
                        }
                        Selector(
                            options = ScheduleWhen.entries.map { SelectorOption(it.label, it) },
                            selected = due,
                            onSelect = { due = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = projects.isNotEmpty() && draft.isNotBlank(),
                onClick = { onCreate(project, due.dueAtMs()) },
            ) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScheduledRow(
    row: MobileScheduledRow,
    onCommand: (String, List<String>, String?) -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                row.prompt.ifBlank { "(empty prompt)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    // Which repo this is for, first: two prompts from different projects were
                    // the same row twice, and the project is what tells them apart.
                    row.projectPath?.takeIf { it.isNotBlank() }?.let {
                        append(it.substringAfterLast('/')).append(" · ")
                    }
                    // The same vocabulary as the desktop chip; a phone that renamed these
                    // states would be a second product.
                    append(row.state)
                    if (row.repeating) append(" · repeating")
                    append(" · due ").append(Times.clock(row.dueAtMs))
                    // A row bound to a conversation continues it; one without starts a chat.
                    if (row.sessionId.isNullOrBlank()) append(" · new chat")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (row.state == MobileScheduledRow.PAUSED) {
                    TextButton(onClick = {
                        onCommand(MobileScheduledCommand.RESUME, listOf(row.id), "Resumed")
                    }) { Text("Resume") }
                } else {
                    TextButton(onClick = {
                        onCommand(MobileScheduledCommand.PAUSE, listOf(row.id), "Paused")
                    }) { Text("Pause") }
                }
                TextButton(onClick = {
                    onCommand(MobileScheduledCommand.RUN_NOW, listOf(row.id), "Running it now")
                }) { Text("Run now") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
