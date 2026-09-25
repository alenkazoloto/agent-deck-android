package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import dev.agentdeck.companion.data.CommitStagedSheet

/**
 * The desk's "Commit Staged Changes" dialog as a sheet: the subject with its write control, the
 * description, the rename the desk offers for an unpublished branch, and the staged files — read
 * only, because what is committed is exactly the index, staged on the machine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitStagedSheetView(
    sheet: CommitStagedSheet,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onRename: (Boolean) -> Unit,
    onRenameTo: (String) -> Unit,
    onWrite: () -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("commit-staged-sheet"),
        ) {
            Text("Commit staged changes", style = MaterialTheme.typography.titleMedium)
            val preview = sheet.preview
            if (preview == null || sheet.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            stagedSummary(sheet)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sheet.subject,
                    onValueChange = onSubject,
                    label = { Text("Subject") },
                    singleLine = true,
                    enabled = !sheet.busy,
                    modifier = Modifier.weight(1f).testTag("commit-staged-subject"),
                )
                DeckIconButton(
                    label = WRITE_LABEL,
                    icon = Icons.Filled.Edit,
                    onClick = onWrite,
                    enabled = sheet.canWrite,
                    modifier = Modifier.testTag("commit-staged-write"),
                )
            }
            OutlinedTextField(
                value = sheet.body,
                onValueChange = onBody,
                label = { Text("Description") },
                minLines = 3,
                maxLines = 8,
                enabled = !sheet.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("commit-staged-body"),
            )
            if (preview?.renamable == true) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp)
                        .toggleable(value = sheet.rename, enabled = !sheet.busy, role = Role.Checkbox, onValueChange = onRename),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = sheet.rename, onCheckedChange = null, enabled = !sheet.busy)
                    Text("Rename the branch", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (sheet.rename) {
                    OutlinedTextField(
                        value = sheet.renameTo,
                        onValueChange = onRenameTo,
                        label = { Text("New branch name") },
                        singleLine = true,
                        enabled = !sheet.busy,
                        modifier = Modifier.fillMaxWidth().testTag("commit-staged-rename"),
                    )
                }
            }
            if (preview != null) {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                preview.files.forEach { file ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp)) {
                        Text(file.path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        commitStatus(file)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            sheet.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp).testTag("commit-staged-error"),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onCommit, enabled = sheet.canCommit, modifier = Modifier.testTag("commit-staged-confirm")) {
                    Text("Commit")
                }
            }
        }
    }
}

/** The desk dialog's summary line: how many files, on which branch, or what is being written. */
fun stagedSummary(sheet: CommitStagedSheet): String? {
    if (sheet.writing) return "Writing the message…"
    val preview = sheet.preview ?: return null
    val files = preview.files.size
    val where = preview.branch?.let { " on $it" }.orEmpty()
    return if (files == 1) "1 staged file$where" else "$files staged files$where"
}

const val WRITE_LABEL = "Write the message from the staged diff"
