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
import com.github.claudeagents.core.mobile.MobileReviewCommitFile
import com.github.claudeagents.core.mobile.MobileReviewCommitPreview
import dev.agentdeck.companion.data.CommitSheet

/**
 * "Commit changes" over the machine's preview: the message the desk would seed, and the
 * conversation's own files, ticked as the desk's commit UI ticks them. Nothing else in the
 * repository is listed, because nothing else can be committed from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitSheetView(
    sheet: CommitSheet,
    onMessage: (String) -> Unit,
    onToggle: (String) -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("commit-sheet"),
        ) {
            Text("Commit changes", style = MaterialTheme.typography.titleMedium)
            val preview = sheet.preview
            if (preview == null || sheet.committing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            preview?.branch?.let {
                Text(
                    "On $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OutlinedTextField(
                value = sheet.message,
                onValueChange = onMessage,
                label = { Text("Commit message") },
                minLines = 3,
                maxLines = 8,
                enabled = !sheet.committing,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("commit-message"),
            )
            if (preview != null) {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                preview.files.forEach { file ->
                    FileChoice(file, checked = file.path in sheet.chosen, enabled = !sheet.committing) { onToggle(file.path) }
                }
                leftOut(preview)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            sheet.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp).testTag("commit-error"),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onCommit, enabled = sheet.canCommit, modifier = Modifier.testTag("commit-confirm")) {
                    Text(commitLabel(sheet.chosen.size))
                }
            }
        }
    }
}

@Composable
private fun FileChoice(file: MobileReviewCommitFile, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val allowed = enabled && file.committable
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = allowed, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = allowed)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(file.path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            commitStatus(file)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What a row's status means for this commit; a plain modification needs no words. */
fun commitStatus(file: MobileReviewCommitFile): String? = when (file.status) {
    MobileReviewCommitFile.UNTRACKED -> "New, not in git yet"
    MobileReviewCommitFile.ADDED -> "New file"
    MobileReviewCommitFile.DELETED -> "Deleted"
    MobileReviewCommitFile.RENAMED -> "Renamed"
    MobileReviewCommitFile.CONFLICTED -> "Has merge conflicts. Resolve them in the IDE first."
    else -> null
}

/** The chat's files this commit cannot include, counted rather than silently missing. */
fun leftOut(preview: MobileReviewCommitPreview): String? = listOfNotNull(
    preview.alreadyCommitted.takeIf { it > 0 }?.let { "$it already committed" },
    preview.outside.takeIf { it > 0 }?.let { "$it outside this repository" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Also changed by this chat: ")

fun commitLabel(files: Int): String = if (files == 1) "Commit 1 file" else "Commit $files files"
