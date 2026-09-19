package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.OutgoingQueue
import dev.agentdeck.companion.data.OutgoingSend

/**
 * What this conversation still owes the machine, above the composer that owes it.
 *
 * Absent while the queue is empty, which is nearly always: a permanent "nothing queued" row
 * would be the noise progressive disclosure exists to refuse. The three actions appear only on
 * a *parked* item, because that is the only state where the app has stopped deciding and the
 * reader has to.
 */
@Composable
fun OutgoingChip(
    queued: List<OutgoingSend>,
    delivering: String?,
    onRetry: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val line = OutgoingQueue.chipLine(queued, delivering) ?: return
    val parked = queued.firstOrNull { it.parked }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            line,
            style = MaterialTheme.typography.labelMedium,
            color = if (parked == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = line },
        )
        if (parked != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onRetry(parked.clientMessageId) }) { Text("Retry") }
                TextButton(onClick = { onEdit(parked.clientMessageId) }) { Text("Edit") }
                TextButton(onClick = { onDiscard(parked.clientMessageId) }) { Text("Discard") }
            }
            // The one sentence a reader cannot work out for themselves: the machine may already
            // have run this, so "Retry" is a decision rather than a repair.
            if (parked.uncertain) {
                Text(
                    "This machine may already have received it — check the conversation first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
