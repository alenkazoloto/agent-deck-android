package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileBoardTask
import com.github.claudeagents.core.mobile.MobileOrchestration
import com.github.claudeagents.core.mobile.MobileTeamBoard
import com.github.claudeagents.core.mobile.MobileWorkflowRun

/**
 * Settings › Agents › "Agent teams and workflow runs": the desk's Orchestration page, read-only.
 *
 * Every state is a word, never a colour alone — a task says "In progress" or "Done", a failed
 * run says why it failed — and the machine's own sentence rides under a task that has one
 * ("Stalled — no update for 1h 35m"). Read again each time the sheet opens, so a board a teammate
 * just moved is what the reader sees. There is nothing to tap on a row: the desk page has no
 * control to mirror, and inventing one here would be a phone-only graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrchestrationSheet(onLoad: suspend () -> MobileOrchestration?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileOrchestration?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                "Agent teams and workflow runs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val result = loaded
            when {
                loading -> Note("Reading teams and workflow runs…")
                result == null -> Note("The machine did not answer. Close this and try again.")
                result.unavailable != null && result.empty -> Note(result.unavailable!!)
                result.empty -> Note("No agent teams or workflow runs on this machine. Teams are experimental in Claude Code, and runs appear once a workflow has finished.")
                else -> Content(result)
            }
        }
    }
}

@Composable
private fun Content(result: MobileOrchestration) {
    LazyColumn(Modifier.heightIn(max = 520.dp).testTag("orchestration-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(result.boards, key = { "board-${it.key}" }) { Board(it) }
        if (result.runs.isNotEmpty()) {
            item(key = "runs-head") { Head("Workflow runs") }
            items(result.runs, key = { "run-${it.project}-${it.runId}" }) { Run(it) }
        }
        if (result.truncated) {
            item(key = "truncated") { Note("More is on the desk's Settings › Orchestration page.") }
        }
    }
}

@Composable
private fun Head(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Board(board: MobileTeamBoard) {
    Column(Modifier.fillMaxWidth().testTag("orchestration-board")) {
        Head("${board.title} — ${board.done} of ${board.total} done")
        if (board.members.isNotEmpty()) Detail("Members: " + board.members.joinToString(", "))
        if (board.idle.isNotEmpty()) Detail("Idle: " + board.idle.joinToString(", "))
        board.tasks.forEach { Task(it) }
        if (board.total > board.tasks.size) Detail("${board.total - board.tasks.size} more tasks on the desk.")
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Task(task: MobileBoardTask) {
    Column(Modifier.fillMaxWidth().padding(start = (task.depth.coerceAtMost(4) * 16).dp, top = 6.dp).testTag("orchestration-task")) {
        Text(task.subject, style = MaterialTheme.typography.bodyLarge)
        Detail(listOfNotNull(statusWord(task.status), task.owner).joinToString(" · "))
        task.note?.let { Detail(it) }
    }
}

@Composable
private fun Run(run: MobileWorkflowRun) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("orchestration-run")) {
        Text(run.label, style = MaterialTheme.typography.bodyLarge)
        Detail(
            listOfNotNull(
                run.status,
                "${run.agents} agent${if (run.agents == 1) "" else "s"}".takeIf { run.agents > 0 },
                "${run.agentsFailed} failed".takeIf { run.agentsFailed > 0 },
                run.durationMs.takeIf { it > 0 }?.let(::durationWords),
            ).joinToString(" · "),
        )
        run.project.takeIf { it.isNotBlank() }?.let { Detail(it.substringAfterLast('/')) }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** The board file's own status, spelled the way the desk table spells it. */
internal fun statusWord(status: String): String = when (status) {
    "pending" -> "Pending"
    "in_progress" -> "In progress"
    "completed" -> "Done"
    else -> "Unknown"
}

/** A run takes minutes; an agent seconds — one ruler that reads both. */
internal fun durationWords(ms: Long): String {
    val seconds = ms / 1_000
    return when {
        seconds < 60 -> "${seconds.coerceAtLeast(1)}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}
