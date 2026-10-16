package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileContextBreakdown
import com.github.claudeagents.core.mobile.MobileContextQuery
import dev.agentdeck.companion.data.ContextSheet

/**
 * The desk context gauge's grid for one chat (`/v1/context`): how the window is divided, then the
 * instruction files that loaded. Every figure and sentence is the machine's, so a row reads as it
 * does on the desk; a `≈` figure is an estimate and the note under the files says how it is made.
 * A chat with no reply yet says so rather than showing an empty grid.
 */
@Composable
internal fun ContextDialog(sheet: ContextSheet, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("context-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("Context", style = MaterialTheme.typography.headlineSmall)
                    if (sheet.title.isNotBlank()) {
                        Text(
                            sheet.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // One scrolling body under the fixed button: at 200% text the grid outgrows the dialog.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val grid = sheet.breakdown
                    when {
                        sheet.error != null -> Notice(sheet.error, error = true)
                        grid == null -> Notice("Reading this conversation…")
                        !grid.measured -> Notice(MobileContextQuery.NOT_MEASURED)
                        else -> Grid(grid)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("context-notice"),
    )
}

@Composable
private fun Grid(grid: MobileContextBreakdown) {
    Text(grid.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("context-headline"))
    grid.overflowNote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    grid.rows.forEach { row ->
        // One announcement per row — "Messages, 41.2K · 21%" — and the bar, which repeats it, stays silent.
        Column(Modifier.semantics(mergeDescendants = true) {}.testTag("context-row").padding(start = if (row.nested) 16.dp else 0.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.nested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(row.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!row.nested) {
                LinearProgressIndicator(
                    progress = { row.sharePercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics {},
                )
            }
            if (row.detail.isNotBlank()) {
                Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (grid.files.isNotEmpty()) {
        Text("Instruction files loaded", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        grid.files.forEach { file ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("context-file"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(file.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
                Text(file.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (grid.moreFiles > 0) {
            Text("and ${grid.moreFiles} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    grid.estimateNote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
