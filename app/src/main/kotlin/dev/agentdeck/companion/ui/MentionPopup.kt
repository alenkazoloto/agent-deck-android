package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
 */
@Composable
fun MentionPopup(
    paths: List<String>,
    indexing: Boolean,
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
            indexing -> Column(Modifier.padding(12.dp)) {
                Text(
                    "The IDE is still indexing this project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            paths.isEmpty() -> Column(Modifier.padding(12.dp)) {
                Text(
                    "No file matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(Modifier.heightIn(max = 180.dp)) {
                items(paths, key = { it }) { path ->
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
            }
        }
    }
}
