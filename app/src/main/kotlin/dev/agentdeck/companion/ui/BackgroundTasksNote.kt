package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileBackgroundTask

/**
 * The desk's `/tasks` list, read-only: what the CLI has running in the background of this chat —
 * agents, shell jobs, monitors — so a chat that looks idle is not read as finished. Stopping one
 * stays on the desk, where the run has the channel for it.
 */
@Composable
internal fun BackgroundTasksNote(tasks: List<MobileBackgroundTask>) {
    val heading = backgroundTasksText(tasks.size) ?: return
    Column(
        Modifier.fillMaxWidth().padding(start = 50.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$heading: ${tasks.joinToString("; ", transform = ::backgroundTaskLine)}" },
    ) {
        Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        backgroundTaskLines(tasks).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
