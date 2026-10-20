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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileCommand

/**
 * The `/` and `$` completion list, stacked above the composer for the reason `MentionPopup`
 * records. Its rows are only what a phone send runs (`/v1/commands`); a desk-only command such
 * as `/model` has its own control on this screen instead.
 *
 * Three states, as the `@` popup keeps them apart: loading, a catalogue that could not be read,
 * and a catalogue that has no match for what was typed.
 */
@Composable
fun CommandPopup(
    rows: List<MobileCommand>,
    loading: Boolean,
    unreachable: Boolean,
    onPick: (MobileCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        when {
            loading -> Note("Reading this chat's commands…")
            unreachable -> Note("Commands could not be read right now. Type the command in full, or start it again to retry.")
            rows.isEmpty() -> Note("No command or skill matches.")
            else -> LazyColumn(Modifier.heightIn(max = 220.dp)) {
                // Keyed by what a row writes: two Codex skills may share a name.
                items(rows, key = { it.written }) { row ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(row) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row {
                            Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            row.argumentHint?.let {
                                Text(
                                    " $it",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (row.description.isNotEmpty()) {
                            Text(
                                row.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Column(Modifier.padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
