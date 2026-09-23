package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileReviewRevertFile
import dev.agentdeck.companion.data.RevertSheet

/**
 * "Revert to session start" over the machine's preview: what the desk's revert would restore,
 * delete and skip, and the notes its dialog prints, above the files. The confirm button names the
 * count, so the last tap says what it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevertSheetView(
    sheet: RevertSheet,
    onToggle: (String) -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("revert-sheet"),
        ) {
            Text("Revert to session start", style = MaterialTheme.typography.titleMedium)
            val preview = sheet.preview
            if (preview == null || sheet.reverting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (preview != null) {
                preview.notes.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(top = 12.dp))
                preview.files.forEach { file ->
                    FileChoice(file, checked = file.path in sheet.chosen, enabled = !sheet.reverting) { onToggle(file.path) }
                }
            }
            sheet.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp).testTag("revert-error"),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = onRevert,
                    enabled = sheet.canRevert,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("revert-confirm"),
                ) { Text(revertLabel(sheet.chosen.size)) }
            }
        }
    }
}

@Composable
private fun FileChoice(file: MobileReviewRevertFile, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val allowed = enabled && file.revertable
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = allowed, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = allowed)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(file.path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(revertAction(file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What reverting this row does; the desk's dialog groups its list under the same three words. */
fun revertAction(file: MobileReviewRevertFile): String = when (file.action) {
    MobileReviewRevertFile.RESTORE -> "Restore"
    MobileReviewRevertFile.DELETE -> "Delete (created by this chat)"
    else -> "Skip (no recorded session-start content)"
}

fun revertLabel(files: Int): String = if (files == 1) "Revert 1 file" else "Revert $files files"
