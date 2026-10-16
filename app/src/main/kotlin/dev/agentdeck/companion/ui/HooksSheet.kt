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
import com.github.claudeagents.core.mobile.MobileHooks

/**
 * The desk's `/hooks` opened from a Claude chat's `/` popup: the hook commands Claude Code would run for
 * the chat's project, grouped by event in the desk's order, each with its matcher, the origin that declared
 * it and — when the entry cannot run — the machine's sentence for why. Read-only: the desk's Hooks page
 * edits, and a command's arguments never leave the machine, so a row names only its program.
 *
 * Read each time the sheet opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HooksSheet(onLoad: suspend () -> MobileHooks?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileHooks?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("hooks-sheet"),
        ) {
            Text("Hooks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val hooks = loaded
            when {
                loading -> HooksNote("Reading the hooks…")
                hooks == null -> HooksNote("The machine did not answer. Close this and try again.")
                hooks.notice != null -> HooksNote(hooks.notice!!)
                else -> {
                    hooks.notes.forEach { HooksNote(it) }
                    if (hooks.rows.isEmpty()) HooksNote("No hooks are configured for this project.")
                    // Rows arrive in the desk's event order, so a run of equal events is one group.
                    var previous: String? = null
                    hooks.rows.forEachIndexed { index, row ->
                        if (row.event != previous) {
                            Text(
                                row.event,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 16.dp).testTag("hooks-event-${row.event}"),
                            )
                            previous = row.event
                        }
                        Column(Modifier.testTag("hooks-row-$index").padding(top = 8.dp)) {
                            Text(row.program, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                hookDetail(row),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            row.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

/** "Bash · Project · 30 s", or "Every input · Project" and "· Does not run" when policy or a schema fault stops it. */
internal fun hookDetail(row: MobileHooks.Row): String = listOfNotNull(
    row.matcher.ifEmpty { "Every input" },
    row.origin,
    row.timeoutSeconds?.let { "$it s" },
    "Does not run".takeIf { !row.runs },
).joinToString(" · ")

@Composable
private fun HooksNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
