package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileIssue

/**
 * The `#` issue and pull-request list, stacked above the composer for the reason `MentionPopup`
 * records. Unlike the other popups it has no "no match" line, as the desk's has none: most
 * machines configure no tracker, and a box saying so under every `#1` would be noise. Rows or,
 * when a tracker did not answer, one line saying so.
 */
@Composable
fun IssuePopup(
    rows: List<MobileIssue>,
    unavailable: Boolean,
    onPick: (MobileIssue) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        if (rows.isEmpty() && unavailable) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "The issue tracker did not answer. Keep typing to ask again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(rows, key = { "${it.provider}:${it.id}" }) { issue -> IssueRow(issue, onPick) }
            }
        }
    }
}

/** Id, summary, state — the desk row's order; a closed one struck through, as there. */
@Composable
private fun IssueRow(issue: MobileIssue, onPick: (MobileIssue) -> Unit) {
    val kind = if (issue.pullRequest) "Pull request" else "Issue"
    val struck = if (issue.resolved) TextDecoration.LineThrough else null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(issue) }
            .clearAndSetSemantics {
                contentDescription = listOfNotNull("$kind ${issue.display}", issue.summary, issue.state).joinToString(", ")
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row {
            Text(
                issue.display,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textDecoration = struck,
                maxLines = 1,
            )
            Text(
                "  " + listOfNotNull(if (issue.pullRequest) "PR" else null, issue.state).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (issue.summary.isNotEmpty()) {
            Text(
                issue.summary,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = struck,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
