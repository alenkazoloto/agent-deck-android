package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.composer.ComposerStashQueue
import com.github.claudeagents.core.composer.StashedPrompt

/**
 * One conversation's stash as the composer sees it: what is parked, newest first, and the three
 * things the reader can do with it. Null on the composer means the surface is not offered at all.
 */
class ComposerStashActions(
    val entries: List<StashedPrompt>,
    val onStash: () -> Unit,
    val onRestore: (String) -> Unit,
    val onDiscard: (String) -> Unit,
)

/**
 * The desk's stash badge on the phone: a chip among the quick replies, shown only while something
 * is parked and the composer is empty, naming how many. It opens the list rather than restoring
 * the newest outright so that every entry, and discarding one, is one sheet away — the desk's
 * rule that nothing parked is unreachable.
 */
@Composable
internal fun StashedPromptsChip(stash: ComposerStashActions) {
    // Also what closes the sheet after the last discard: an empty list is a dead end.
    if (stash.entries.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    SuggestionChip(
        onClick = { open = true },
        label = { Text(stashedSentence(stash.entries.size)) },
        shape = CircleShape,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = null,
    )
    if (open) {
        StashedPromptsSheet(
            stash = stash,
            onRestore = { id -> open = false; stash.onRestore(id) },
            onDismiss = { open = false },
        )
    }
}

internal fun stashedSentence(count: Int) = if (count == 1) "1 message stashed" else "$count messages stashed"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StashedPromptsSheet(
    stash: ComposerStashActions,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = LocalNow.current()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Stashed messages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(stash.entries, key = { _, entry -> entry.id }) { index, entry ->
                    if (index > 0) HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(onClickLabel = "Put back in the composer") { onRestore(entry.id) }
                                .padding(vertical = 14.dp),
                        ) {
                            Text(
                                ComposerStashQueue.snippet(entry.text),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Times.relative(entry.atMs, now).takeIf { it.isNotEmpty() }?.let { age ->
                                Text(
                                    if (age == "now") "Stashed just now" else "Stashed $age ago",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Named by its message, so moving between the ✕ buttons says which one each drops.
                        DeckIconButton(
                            "Discard “${ComposerStashQueue.snippet(entry.text).take(40)}”",
                            Icons.Filled.Close,
                            onClick = { stash.onDiscard(entry.id) },
                        )
                    }
                }
            }
        }
    }
}
