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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.github.claudeagents.core.mobile.MobileReviewRevertChoice
import com.github.claudeagents.core.mobile.MobileReviewRevertFile
import com.github.claudeagents.core.mobile.MobileReviewRevertRequest
import dev.agentdeck.companion.data.RevertSheet

/**
 * "Revert to session start" over the machine's preview: what the desk's revert would restore,
 * delete and skip, and the notes its dialog prints, above the files. The confirm button names the
 * count, so the last tap says what it does.
 *
 * Under it, when the machine lists them, the chat's requests with the desk's two per-request
 * reverts; choosing one turns this sheet into that revert's preview, with Back to the whole session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevertSheetView(
    sheet: RevertSheet,
    onToggle: (String) -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
    onChoose: (scope: String, request: MobileReviewRevertChoice?) -> Unit = { _, _ -> },
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("revert-sheet"),
        ) {
            val request = sheet.request
            if (sheet.scope == MobileReviewRevertRequest.SESSION || request == null) {
                Text("Revert to session start", style = MaterialTheme.typography.titleMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeckIconButton(
                        label = "Back to reverting the whole session",
                        icon = Icons.Filled.ArrowBack,
                        onClick = { onChoose(MobileReviewRevertRequest.SESSION, null) },
                        enabled = !sheet.reverting,
                    )
                    Text(revertTitle(sheet.scope, request.number), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "“${request.prompt}”",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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
                    FileChoice(file, sheet.scope, checked = file.path in sheet.chosen, enabled = !sheet.reverting) { onToggle(file.path) }
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
            if (sheet.scope == MobileReviewRevertRequest.SESSION && sheet.requests.isNotEmpty() && sheet.preview != null) {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                Text(
                    "Or revert one request",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                sheet.requests.forEach { choice -> RequestChoice(choice, enabled = !sheet.reverting, onChoose = onChoose) }
            }
        }
    }
}

/** One request, numbered as the desk's "Group by request", with its two reverts. */
@Composable
private fun RequestChoice(
    choice: MobileReviewRevertChoice,
    enabled: Boolean,
    onChoose: (String, MobileReviewRevertChoice?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("revert-request-${choice.number}")) {
        Text(
            "${choice.number}. ${choice.prompt}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (choice.files == 1) "1 file" else "${choice.files} files",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onChoose(MobileReviewRevertRequest.REQUEST, choice) }, enabled = enabled) {
                Text("Undo this request")
            }
            if (choice.laterFiles > 0) {
                TextButton(onClick = { onChoose(MobileReviewRevertRequest.AFTER, choice) }, enabled = enabled) {
                    Text("Revert to after it")
                }
            }
        }
    }
}

/** The sheet's title for a per-request scope, in the desk menu's words. */
fun revertTitle(scope: String, number: Int): String =
    if (scope == MobileReviewRevertRequest.AFTER) "Revert to after request $number" else "Undo request $number's changes"

@Composable
private fun FileChoice(file: MobileReviewRevertFile, scope: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val allowed = enabled && file.revertable
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = allowed, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = allowed)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(file.path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(revertAction(file, scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What reverting this row does; the desk's dialog groups its list under the same three words, with
 * the same reasons per revert (`SessionRevert`'s `deleteNote`/`skipNote`).
 */
fun revertAction(file: MobileReviewRevertFile, scope: String = MobileReviewRevertRequest.SESSION): String = when (file.action) {
    MobileReviewRevertFile.RESTORE -> "Restore"
    MobileReviewRevertFile.DELETE -> when (scope) {
        MobileReviewRevertRequest.REQUEST -> "Delete (created by the request)"
        MobileReviewRevertRequest.AFTER -> "Delete (created after this request)"
        else -> "Delete (created by this chat)"
    }
    else -> when (scope) {
        MobileReviewRevertRequest.REQUEST -> "Skip (later changes overlap or the content isn't reconstructable)"
        MobileReviewRevertRequest.AFTER -> "Skip (content at that point isn't reconstructable)"
        else -> "Skip (no recorded session-start content)"
    }
}

fun revertLabel(files: Int): String = if (files == 1) "Revert 1 file" else "Revert $files files"
