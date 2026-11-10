package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileApprovals
import com.github.claudeagents.core.mobile.MobileDebugConfig
import com.github.claudeagents.core.mobile.MobileStatus

/**
 * The desk's Codex `/permissions` opened from a Codex chat's `/` popup: the approval policy chats in the
 * project run under, the configured one, the granular switches and the execpolicy rule files, in the
 * machine's own words. Read-only, as the desk's page is — Codex's own popup writes the policy.
 *
 * Read each time the sheet opens. The machine's sentence replaces the rows when Codex could not be asked.
 */
@Composable
internal fun ApprovalsSheet(onLoad: suspend () -> MobileApprovals?, onDismiss: () -> Unit) {
    MachineRowsSheet(
        title = "Permissions", reading = "Reading the approval policy…", tag = "approvals",
        onLoad = { onLoad()?.let { it.rows to it.notice } }, onDismiss = onDismiss,
    )
}

/**
 * The desk's Codex `/debug-config` opened from a Codex chat's `/` popup: whether the project is trusted, the
 * config layers Codex resolved lowest precedence first, the settings each one won and any administrator
 * requirements, in the machine's words. Read-only, key names only — a setting's value stays in its file.
 */
@Composable
internal fun DebugConfigSheet(onLoad: suspend () -> MobileDebugConfig?, onDismiss: () -> Unit) {
    MachineRowsSheet(
        title = "Config layers", reading = "Reading the config layers…", tag = "debug-config",
        onLoad = { onLoad()?.let { it.rows to it.notice } }, onDismiss = onDismiss,
    )
}

/** A read-only sheet of the machine's [MobileStatus.Row]s: label over value over detail, or its sentence, or "did not answer". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachineRowsSheet(
    title: String,
    reading: String,
    tag: String,
    onLoad: suspend () -> Pair<List<MobileStatus.Row>, String?>?,
    onDismiss: () -> Unit,
) {
    var loaded by remember { mutableStateOf<Pair<List<MobileStatus.Row>, String?>?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("$tag-sheet"),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val answer = loaded
            when {
                loading -> RowsNote(reading)
                answer == null -> RowsNote("The machine did not answer. Close this and try again.")
                answer.second != null -> RowsNote(answer.second!!)
                else -> answer.first.forEach { row ->
                    Column(Modifier.testTag("$tag-row-${row.label}").padding(top = 12.dp)) {
                        Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.value, style = MaterialTheme.typography.bodyLarge)
                        row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
