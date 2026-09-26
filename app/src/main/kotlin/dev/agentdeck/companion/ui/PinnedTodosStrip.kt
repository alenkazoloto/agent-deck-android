package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobilePinnedTodos

/**
 * The desk's pinned todo line — "Todos 3/7 · Running tests…" above the message box while the agent's last
 * list still has work open, unfolding into the checklist. The transcript keeps its own checklist on the turn
 * that wrote it; this is the list as it stands *now*, so a long run does not bury it under the calls that
 * followed. Drawn only where Settings › Machine's "Pinned todo list" is on, and gone once every row is done.
 */
@Composable
internal fun PinnedTodosStrip(summary: MobilePinnedTodos.Summary, resetKey: Any) {
    var open by rememberSaveable(resetKey) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.semantics {
                role = Role.Button
                contentDescription = "The agent's todo list, ${summary.done} of ${summary.total} done. " +
                    if (open) "Tap to fold it." else "Tap to unfold it."
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary.label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.items.take(SHOWN).forEach { todo ->
                    TaskLine(
                        when {
                            todo.done -> TaskMark.DONE
                            todo.active -> TaskMark.ACTIVE
                            else -> TaskMark.PENDING
                        },
                        InlineMarkdown.inline(todo.text),
                    )
                }
                if (summary.items.size > SHOWN) {
                    Text(
                        "and ${summary.items.size - SHOWN} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The desk's own cap on unfolded rows. */
private const val SHOWN = 12
