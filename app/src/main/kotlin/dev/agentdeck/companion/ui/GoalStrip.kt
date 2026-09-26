package dev.agentdeck.companion.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.sessions.ChatGoal

/**
 * The desk's goal bar: while a `/goal` is armed the agent will not stop until its condition holds,
 * which from the phone otherwise reads as a run that will not finish. Drawn only for a page that
 * carries the condition, and gone the moment the CLI records it as met.
 *
 * Clear adds `/goal clear` to the message box — see [dev.agentdeck.companion.data.GoalClear].
 */
@Composable
internal fun GoalStrip(condition: String, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Goal",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            condition,
            Modifier.weight(1f).padding(horizontal = 8.dp).semantics {
                contentDescription = "Goal: ${ChatGoal.barTooltip(condition)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onClear,
            modifier = Modifier.semantics { contentDescription = "Clear goal: adds ${ChatGoal.CLEAR_COMMAND} to your message" },
        ) { Text("Clear") }
    }
}
