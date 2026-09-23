package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.ReviewQueue

/**
 * Finished work waiting for a look, across every project on this machine.
 *
 * Plain rows under a project heading, not cards: this is a list to scan, and one tap lands on
 * the task's changed files (`DeckViewModel.openForReview`). Marking reviewed stays on the
 * Changes half, beside the diff it vouches for — a list-level tick would clear work unseen.
 */
@Composable
fun ReviewScreen(
    snapshot: MobileFleetSnapshot?,
    canReview: Boolean,
    machineName: String?,
    onOpen: (MobileFleetRow) -> Unit,
    onMarkReviewed: (MobileFleetRow) -> Unit,
) {
    if (!canReview) {
        return ReviewEmpty(
            Icons.Filled.Info,
            "Review needs a newer plugin",
            "Update Agents Deck on ${machineName?.takeIf { it.isNotBlank() } ?: "this machine"} " +
                "to read and mark changes here.",
        )
    }
    if (snapshot == null) {
        return ReviewEmpty(Icons.Filled.Info, "Loading your tasks…", "")
    }
    val projects = ReviewQueue.projects(snapshot.rows)
    if (projects.isEmpty()) {
        return ReviewEmpty(
            Icons.Filled.Done,
            "Nothing to review",
            "Finished tasks whose changes you haven't looked at appear here.",
        )
    }
    LazyColumn(Modifier.fillMaxSize()) {
        projects.forEach { project ->
            item(key = "project:${project.name}:${project.rows.first().projectPath}") {
                Text(
                    "${project.name}  ${project.rows.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
            }
            items(project.rows, key = { it.key }) { row ->
                ReviewRow(row, snapshot.generatedAtMs, onOpen, onMarkReviewed)
                HorizontalDivider(
                    Modifier.padding(start = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewRow(
    row: MobileFleetRow,
    generatedAtMs: Long,
    onOpen: (MobileFleetRow) -> Unit,
    onMarkReviewed: (MobileFleetRow) -> Unit,
) {
    val title = row.title.ifBlank { "Untitled chat" }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open its changes") { onOpen(row) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title, ${row.vendor.label()}, finished, ready to review"
                // The one action a reader might want without the diff — the fleet row's long
                // press offers it too — kept off the screen so the visible tap stays "look first".
                customActions = listOf(
                    CustomAccessibilityAction("Mark reviewed without opening") { onMarkReviewed(row); true },
                )
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(row.vendor.label())
                    row.gitBranch?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    Times.relative(row.lastActivityMs, generatedAtMs).takeIf { it.isNotBlank() }
                        ?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ReviewEmpty(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
