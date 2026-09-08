package dev.agentdeck.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledRow
import dev.agentdeck.companion.data.ScheduleWhen

/**
 * What is queued on the machine, and — for the first time — a way to add to it.
 *
 * The screen had none of the fleet's manners: no pull-to-refresh, **Cancel** and **Cancel all**
 * firing straight through with nothing between the thumb and a lost prompt, and rows that named
 * neither project nor branch, so two prompts from different repos were the same row twice.
 *
 * Weight is the second thing it lacked. Every control was the same button: a create action beside
 * a bulk cancel, and per row a **Cancel** that cannot be undone painted exactly like **Pause**.
 * Destructive is `error` and set apart, safe is quiet, and the one thing the screen wants you to
 * do is the only filled control on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    rows: List<MobileScheduledRow>,
    loading: Boolean,
    canCreate: Boolean,
    projects: List<String>,
    /** `/v1/hello`, for the model ladder the create dialog offers. */
    hello: MobileHello?,
    draft: String,
    onDraft: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: (projectPath: String, dueAtMs: Long, model: String?) -> Unit,
    onCommand: (action: String, ids: List<String>, announce: String?) -> Unit,
) {
    var cancelling by remember { mutableStateOf<List<MobileScheduledRow>?>(null) }
    var composing by remember { mutableStateOf(false) }
    val empty = rows.isEmpty() && !loading

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rows.isNotEmpty()) {
                // The ids are the ones on screen. A "cancel everything" request would race
                // the list the user is looking at and kill a row that arrived in between.
                TextButton(
                    onClick = { cancelling = rows },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Cancel all ${rows.size}") }
            }
            // Only where the machine says it will honour a due time. An older plugin ignores
            // the field and runs the prompt immediately, which is not what "schedule" means.
            // Hidden while the list is empty: the empty state carries this action instead.
            if (canCreate && !empty) {
                FilledTonalButton(onClick = { composing = true }) { Text("Schedule a prompt") }
            }
        }

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                when {
                    // A first load used to jump from a blank page to a full list.
                    rows.isEmpty() && loading -> item(key = "skeleton") { ScheduledSkeleton() }
                    empty -> item(key = "empty") { NothingScheduled(canCreate) { composing = true } }
                    else -> items(rows, key = { it.id }) { row ->
                        ScheduledRow(row, onCommand) { cancelling = listOf(row) }
                    }
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
            hello = hello,
            draft = draft,
            onDraft = onDraft,
            onDismiss = { composing = false },
            onCreate = { project, dueAtMs, model ->
                composing = false
                onCreate(project, dueAtMs, model)
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
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Cancel them") }
        },
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
/**
 * The vendor a queued prompt runs under.
 *
 * A constant rather than a selector because this dialog has never offered a choice — the
 * request it posts has always named Claude — and the model ladder has to come from *some*
 * vendor. Naming it here is what makes the omission visible; a picker is its own decision.
 */
private val SCHEDULED_VENDOR = AgentVendor.CLAUDE

@Composable
private fun ScheduleDialog(
    projects: List<String>,
    hello: MobileHello?,
    draft: String,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, Long, String?) -> Unit,
) {
    var project by remember { mutableStateOf(projects.firstOrNull().orEmpty()) }
    var due by remember { mutableStateOf(ScheduleWhen.entries.first()) }
    // Null is "whatever the machine is set to", which is what this dialog always sent.
    var model by remember { mutableStateOf<String?>(null) }
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
                    Column(
                        Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Shown at one project too: where the prompt is about to run is state
                        // the user acts on, and hiding it left them guessing at the machine.
                        // Stacked rather than side by side because both pills carry a name and
                        // a dialog at font scale 1.3 has no room for two.
                        Selector(
                            options = projects.map { SelectorOption(it.substringAfterLast('/'), it) },
                            selected = project,
                            onSelect = { project = it },
                            prefix = "Project",
                        )
                        Selector(
                            options = ScheduleWhen.entries.map { SelectorOption(it.label, it) },
                            selected = due,
                            onSelect = { due = it },
                        )
                        // The same control the new-chat screen draws, over the same field of
                        // the same request. Two pickers over one wire field is how they drift.
                        ModelSelector(
                            hello = hello,
                            vendor = SCHEDULED_VENDOR,
                            selected = model,
                            onSelect = { model = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = projects.isNotEmpty() && draft.isNotBlank(),
                onClick = { onCreate(project, due.dueAtMs(), model) },
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
    // "Due today" is the reader's question, so this one is the phone's clock — unlike a
    // fleet row's age, which is the machine's stamp measured against the machine's snapshot.
    val nowMs = LocalNow.current()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusChip(row.state)
                Column(Modifier.weight(1f)) {
                    Text(
                        row.prompt.ifBlank { "(empty prompt)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildList {
                            // Which repo this is for, first: two prompts from different projects
                            // were the same row twice, and the project is what tells them apart.
                            row.projectPath?.takeIf { it.isNotBlank() }
                                ?.let { add(it.substringAfterLast('/')) }
                            if (row.repeating) add("repeating")
                            // Already the *next* occurrence for a repeating row: the machine
                            // advances `dueAtMs` as the row fires, so this is never a moment
                            // that has passed.
                            add("due ${Times.clock(row.dueAtMs, nowMs)}")
                            // A row bound to a conversation continues it; one without starts a chat.
                            if (row.sessionId.isNullOrBlank()) add("new chat")
                            // A run that went fine is metadata; the one that did not gets its
                            // own line below, because it is the only one that changes a decision.
                            if (row.lastRunAtMs > 0 && !row.lastRunFailed) {
                                add("ran ${Times.clock(row.lastRunAtMs, nowMs)}")
                            }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // The past tense the wire could not carry until MP-10. Without it a nightly
                    // prompt that failed reads exactly like one that has never run — both say
                    // `queued`, because that is the only tense `state` has.
                    if (row.lastRunAtMs > 0 && row.lastRunFailed) {
                        Text(
                            "Last run failed · ${Times.clock(row.lastRunAtMs, nowMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.state == MobileScheduledRow.PAUSED) {
                    TextButton(
                        onClick = { onCommand(MobileScheduledCommand.RESUME, listOf(row.id), "Resumed") },
                        colors = safeActionColors(),
                    ) { Text("Resume") }
                } else {
                    TextButton(
                        onClick = { onCommand(MobileScheduledCommand.PAUSE, listOf(row.id), "Paused") },
                        colors = safeActionColors(),
                    ) { Text("Pause") }
                }
                TextButton(
                    onClick = { onCommand(MobileScheduledCommand.RUN_NOW, listOf(row.id), "Running it now") },
                    colors = safeActionColors(),
                ) { Text("Run now") }
                // Separated as well as tinted: two of these three actions undo themselves and
                // this one does not, and colour alone does not survive a greyscale phone.
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.semantics { contentDescription = "Cancel this prompt" },
                ) { Text("Cancel") }
            }
        }
    }
}

/** Pause, Resume and Run now: reversible, so they read as quieter than the one that is not. */
@Composable
private fun safeActionColors() = ButtonDefaults.textButtonColors(
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * The row's state, rendered as a state instead of a word inside a sentence.
 *
 * The vocabulary is the desktop chip's — a phone that renamed `running`/`paused`/`queued` would
 * be a second product — so the *word* is what separates two rows, and the tint only reinforces
 * it. A string this build does not know is drawn as sent, outlined rather than tinted: the
 * plugin on the other end can be newer than the phone, and dropping the word would be a lie.
 */
@Composable
private fun StatusChip(state: String) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        MobileScheduledRow.RUNNING -> scheme.primaryContainer to scheme.onPrimaryContainer
        MobileScheduledRow.QUEUED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        MobileScheduledRow.PAUSED -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        else -> Color.Transparent to scheme.onSurfaceVariant
    }
    Surface(
        // A Surface rather than an AssistChip: a chip's height is fixed at 32 dp and this label
        // has to grow with the user's font scale.
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (container == Color.Transparent) BorderStroke(1.dp, scheme.outline) else null,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Status: $state" },
    ) {
        Text(
            state,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * An empty queue, and the one gesture that fills it.
 *
 * Where the machine cannot take a scheduled prompt the button is not offered and the reason is,
 * because "nothing here" and "this machine cannot do this" are not the same page.
 */
@Composable
private fun NothingScheduled(canCreate: Boolean, onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.DateRange,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "Nothing is scheduled on this machine.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            if (canCreate) "Queue a prompt and the machine runs it when it comes due."
            else "Scheduling from the phone needs a newer plugin on this machine.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (canCreate) {
            FilledTonalButton(onClick = onCreate) { Text("Schedule a prompt") }
        }
    }
}

/**
 * The shape of the list that is coming, while it is coming.
 *
 * Deliberately still: a shimmer is an infinite animation, and a Roborazzi capture waits for the
 * composition to go idle — it would hang the golden suite rather than fail it.
 */
@Composable
private fun ScheduledSkeleton() {
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Loading scheduled prompts"
        },
    ) {
        repeat(SKELETON_ROWS) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp).heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SkeletonBar(Modifier.width(64.dp), 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBar(Modifier.fillMaxWidth(0.9f), 16.dp)
                        SkeletonBar(Modifier.fillMaxWidth(0.55f), 12.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier, height: Dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)),
    )
}

/** Enough to read as a list and not as one row that failed to load. */
private const val SKELETON_ROWS = 3
