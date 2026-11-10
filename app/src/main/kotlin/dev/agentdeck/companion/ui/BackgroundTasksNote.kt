package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileBackgroundTask

/**
 * The desk's `/tasks` list: what the CLI has running in the background of this chat — agents, shell
 * jobs, monitors — so a chat that looks idle is not read as finished. A row the machine gave an id
 * (it holds that run's control channel, so a Stop can be delivered) gets a Stop button, the popup's
 * Enter; every other row stays read-only, and a phone-started run has none to offer.
 */
@Composable
internal fun BackgroundTasksNote(tasks: List<MobileBackgroundTask>, onStopTask: ((taskId: String, label: String) -> Unit)? = null) {
    val heading = backgroundTasksText(tasks.size) ?: return
    val stoppable = onStopTask != null && tasks.take(BACKGROUND_TASKS_SHOWN).any { it.id != null }
    // Buttons need their own nodes; a note with none reads as one merged sentence, as before.
    val merged = if (stoppable) Modifier else Modifier.semantics(mergeDescendants = true) {
        contentDescription = "$heading: ${tasks.joinToString("; ", transform = ::backgroundTaskLine)}"
    }
    Column(Modifier.fillMaxWidth().padding(start = 50.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).then(merged)) {
        Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!stoppable) {
            backgroundTaskLines(tasks).forEach { Line(it) }
            return@Column
        }
        tasks.take(BACKGROUND_TASKS_SHOWN).forEach { task ->
            val line = backgroundTaskLine(task)
            val id = task.id
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Line(line) }
                if (id != null) {
                    TextButton(
                        onClick = { onStopTask?.invoke(id, line) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop $line" },
                    ) { Text("Stop") }
                }
            }
        }
        backgroundTaskLines(tasks).lastOrNull()?.takeIf { tasks.size > BACKGROUND_TASKS_SHOWN }?.let { Line(it) }
    }
}

@Composable
private fun Line(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
