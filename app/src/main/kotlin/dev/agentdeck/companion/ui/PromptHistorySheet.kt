package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor

/**
 * "Earlier prompts": the desk's Up-recall on a phone. A chip among the quick replies, shown only
 * while the composer is empty — recalling into a half-written message would replace it.
 *
 * The list is read each time the sheet opens, from the same history the desk recalls from, so a
 * prompt just typed at either end is already there.
 */
@Composable
internal fun EarlierPromptsChip(
    vendor: AgentVendor,
    onLoad: suspend () -> List<String>?,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SuggestionChip(
        onClick = { open = true },
        label = { Text("Earlier prompts") },
        shape = CircleShape,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = null,
    )
    if (open) {
        PromptHistorySheet(
            vendor = vendor,
            onLoad = onLoad,
            onPick = { open = false; onPick(it) },
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptHistorySheet(
    vendor: AgentVendor,
    onLoad: suspend () -> List<String>?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var prompts by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        prompts = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                // Codex's history records no project, so its recall spans every project, as its own TUI's does.
                if (vendor == AgentVendor.CODEX) "Earlier Codex prompts" else "Earlier prompts in this project",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val list = prompts
            when {
                loading -> Note("Reading earlier prompts…")
                list == null -> Note("The machine did not answer. Close this and try again.")
                list.isEmpty() -> Note("No earlier prompts yet.")
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(list) { index, prompt ->
                        if (index > 0) HorizontalDivider()
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(prompt) }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}
