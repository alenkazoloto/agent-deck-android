package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.RowAction
import dev.agentdeck.companion.data.RowActions

/**
 * Long-press a row and everything about it is here: what can be done to it, and the metadata
 * the card has no width for.
 *
 * "Mark reviewed" appears here only on a "Done, unreviewed" row of a machine that advertises
 * `review`; "Open on desktop" is still absent, because the bridge serves no focus route. A sheet
 * item that silently did nothing would be worse than its absence — the app would be claiming a
 * capability the machine has not advertised.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowSheet(
    row: MobileFleetRow,
    group: FleetGroup,
    generatedAtMs: Long,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onSnooze: () -> Unit,
    onStop: () -> Unit,
    canReview: Boolean = false,
    onMarkReviewed: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(row.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    rowAnnouncement(row, group),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            RowActions.of(group, canReview).forEach { action ->
                when (action) {
                    RowAction.OPEN -> SheetAction(Icons.Filled.Edit, action.label, onOpen)
                    RowAction.STOP -> SheetAction(Icons.Filled.Close, action.label, onStop)
                    RowAction.SNOOZE -> SheetAction(Icons.Filled.DateRange, action.label, onSnooze)
                    RowAction.MARK_REVIEWED -> SheetAction(Icons.Filled.Check, action.label) {
                        onMarkReviewed()
                        onDismiss()
                    }
                    RowAction.COPY_TITLE -> SheetAction(Icons.Filled.Share, action.label) {
                        clipboard.setText(AnnotatedString(row.title))
                        onDismiss()
                    }
                }
            }

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Meta("Project", row.projectPath)
                row.gitBranch?.takeIf { it.isNotBlank() }?.let { Meta("Branch", it) }
                Meta("Agent", row.vendor.label())
                row.model?.takeIf { it.isNotBlank() }?.let { Meta("Model", it) }
                row.accountLabel?.takeIf { it.isNotBlank() }?.let { Meta("Account", it) }
                Meta("Messages", row.messageCount.toString())
                row.contextPct?.let { Meta("Context", "$it%") }
                Meta("Cost", formatCost(row.costUsd, row.costKnown))
                Meta("Last activity", Times.clock(row.lastActivityMs, generatedAtMs))
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(value, style = MaterialTheme.typography.labelSmall)
    }
}
