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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileMentionRow
import dev.agentdeck.companion.data.MentionMatches

/**
 * The `@` completion list, above the composer.
 *
 * A column in the composer's own layout rather than a floating `Popup`, and that is the
 * decision worth recording: a popup anchored over a phone text field is either under the soft
 * keyboard or over the sentence being written, and the app has no other popup-over-a-field to
 * borrow a placement from. Stacked above the field it pushes the conversation up the way the
 * keyboard already does, so nothing the reader is looking at is covered.
 *
 * It renders three states and never collapses them. Rows are rows; *indexing* is the machine
 * saying it cannot answer yet, which is not "no such file"; and a query that matched nothing
 * says so, so a reader does not sit waiting for a list that has already arrived empty.
 *
 * Git and past-chat rows (`mention-context`) insert the desk's own tokens — `@diff`,
 * `@commit:7233791`, `@chat:1a2b3c4d` — which the machine expands into the diff or the
 * conversation on send; a branch row inserts the branch name, as the desk's does.
 */
@Composable
fun MentionPopup(
    matches: MentionMatches,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        when {
            matches.context.isEmpty() && matches.indexing -> Column(Modifier.padding(12.dp)) { IndexingLine() }

            matches.context.isEmpty() && matches.paths.isEmpty() -> Column(Modifier.padding(12.dp)) {
                Text(
                    "No file matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column {
                MatchList(matches, onPick)
                // Outside the list, so a long list cannot scroll the reason files are missing away.
                if (matches.indexing) Column(Modifier.padding(12.dp)) { IndexingLine() }
            }
        }
    }
}

@Composable
private fun MatchList(matches: MentionMatches, onPick: (String) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 180.dp)) {
        // Files first, then git and chats — the desk's merged order; the machine caps the files
        // at eight when rows follow them, so neither group buries the other.
        items(matches.paths, key = { it }) { path ->
            Text(
                path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                // From the end: two paths in one directory differ in their last
                // segment, and a list ellipsised at the right is a list of prefixes.
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(path) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        items(matches.context, key = { "${it.kind.wire}:${it.token}" }) { row ->
            ContextRow(row, onPick)
        }
    }
}

@Composable
private fun IndexingLine() {
    Text(
        "The IDE is still indexing this project.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The token as the desk's row paints it, then what it names — a commit's subject, a chat's title. */
@Composable
private fun ContextRow(row: MobileMentionRow, onPick: (String) -> Unit) {
    val detail = row.detail.ifBlank { kindWord(row.kind) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(row.token) }
            .clearAndSetSemantics { contentDescription = "${kindWord(row.kind)} ${row.label}, $detail" }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(row.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(
            "  $detail",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun kindWord(kind: MobileMentionRow.Kind): String = when (kind) {
    MobileMentionRow.Kind.WORKING_DIFF -> "Uncommitted changes"
    MobileMentionRow.Kind.BRANCH_DIFF -> "Branch changes"
    MobileMentionRow.Kind.COMMIT -> "Commit"
    MobileMentionRow.Kind.BRANCH -> "Branch"
    MobileMentionRow.Kind.CHAT -> "Past chat"
}
