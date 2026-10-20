package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileStatus
import kotlinx.coroutines.launch

/**
 * The desk's `/status` opened from a chat's `/` popup: Settings › About's Status rows for the chat's
 * project, in the machine's own labels — a fact it could not read is a missing row, never "unknown".
 *
 * Read each time the sheet opens. "Check API connectivity" is the desk's button: one connect to the API
 * host when asked, its row saying how long ago, since a reading nobody requested would go stale unseen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusSheet(onLoad: suspend (check: Boolean) -> MobileStatus?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    val load by rememberUpdatedState(onLoad)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        loaded = load(false)
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("status-sheet")) {
            Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val status = loaded
            when {
                loading -> StatusNote("Reading the status…")
                status == null -> StatusNote("The machine did not answer. Close this and try again.")
                status.notice != null -> StatusNote(status.notice!!)
                else -> {
                    status.rows.forEach { row ->
                        Column(Modifier.testTag("status-row-${row.label}").padding(top = 12.dp)) {
                            Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(row.value, style = MaterialTheme.typography.bodyLarge)
                            row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    TextButton(
                        onClick = {
                            checking = true
                            scope.launch {
                                load(true)?.let { loaded = it }
                                checking = false
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.testTag("status-check"),
                    ) { Text(if (checking) "Checking…" else "Check API connectivity") }
                }
            }
        }
    }
}

@Composable
private fun StatusNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
