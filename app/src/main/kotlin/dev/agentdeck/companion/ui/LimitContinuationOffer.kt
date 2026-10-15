package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.agentdeck.companion.data.AfterReset

/**
 * The desk's limit bar in a chat its account's usage limit stopped: when the limit resets, and
 * "Continue at …", which queues a prompt into this chat for just after (`LimitBarBinder`).
 * A transcript line rather than a strip, so it scrolls away with the turn it follows.
 */
@Composable
internal fun LimitContinuationMarker(accountLabel: String?, reset: AfterReset, onContinue: () -> Unit) {
    val nowMs = LocalNow.current()
    val limit = if (reset.weekly) "weekly usage limit" else "usage limit"
    Column(
        // The bottom room is the jump strip's: it floats over the list's last item, and this one's is a button.
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 72.dp).testTag("limit-continuation"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${accountLabel ?: "This account"} reached its $limit · resets ${Times.clock(reset.resetAtMs, nowMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onContinue, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Continue at ${Times.clock(reset.dueAtMs(), nowMs)}…")
        }
    }
}

/** The prompt is the desk's sentence until edited, and kept as a draft until the machine takes it. */
@Composable
internal fun LimitContinuationDialog(
    reset: AfterReset,
    prompt: String,
    onPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
    onSchedule: () -> Unit,
) {
    val nowMs = LocalNow.current()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Continue after the reset") },
        text = {
            Column {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPrompt,
                    modifier = Modifier.fillMaxWidth().testTag("limit-continuation-prompt"),
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                )
                Text(
                    "Sent to this chat at ${Times.clock(reset.dueAtMs(), nowMs)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = prompt.isNotBlank(), onClick = onSchedule) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    )
}

/** What a conversation needs to offer [LimitContinuationMarker]; null when its account is not at a limit. */
class LimitContinuationOffer(
    val reset: AfterReset,
    val accountLabel: String?,
    val prompt: String,
    val onPrompt: (String) -> Unit,
    val onSchedule: (dueAtMs: Long) -> Unit,
)
