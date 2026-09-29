package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileWorktreeActionRequest
import com.github.claudeagents.core.mobile.MobileWorktreeRow
import dev.agentdeck.companion.data.WorktreeConfirm
import dev.agentdeck.companion.data.WorktreeSheet

/**
 * The desk's "Manage worktrees…" as a sheet: each managed worktree under its desk label, with the
 * Merge, Remove, Prune and change-request entries the menu offers. An action that may not fire stays
 * visible, disabled, with the machine's sentence for why. Every action but Prune turns the sheet into
 * its confirmation, worded as the desk's dialogs and tooltips are, so the last tap names what it does.
 * A created change request is a link the reader taps, where the desk opens its browser unasked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorktreeSheetView(
    sheet: WorktreeSheet,
    onAsk: (MobileWorktreeRow, String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    /** Null from a machine that cannot review change requests from the phone, which then offers none. */
    onReview: ((MobileWorktreeRow) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("worktree-sheet"),
        ) {
            val confirm = sheet.confirm
            if (confirm != null) {
                Confirmation(confirm, sheet.fleet?.requestNoun ?: "pull request", onConfirm, onBack)
                return@Column
            }
            Text("Worktrees", style = MaterialTheme.typography.titleMedium)
            Text(
                sheet.projectPath.trimEnd('/').substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Above the rows: the reader who just confirmed is looking here, not below a long list.
            sheet.link?.let { link ->
                val uri = LocalUriHandler.current
                Text(link.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp).testTag("worktree-link-text"))
                TextButton(onClick = { runCatching { uri.openUri(link.url) } }, modifier = Modifier.testTag("worktree-link")) {
                    Text("Open in browser")
                }
            }
            val fleet = sheet.fleet
            if (fleet == null || sheet.working != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val refused = fleet?.refused
            when {
                fleet == null -> Unit
                refused != null -> Text(refused, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                fleet.rows.isEmpty() -> Text(
                    "No managed worktrees. New chat's Checkout: New worktree creates one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                else -> fleet.rows.forEach { row ->
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    WorktreeRowView(row, fleet.baseBranch, fleet.requestNoun, enabled = sheet.working == null, working = sheet.working == row.path, onAsk = onAsk, onReview = onReview)
                }
            }
            sheet.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp).testTag("worktree-error"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorktreeRowView(
    row: MobileWorktreeRow,
    baseBranch: String?,
    requestNoun: String?,
    enabled: Boolean,
    working: Boolean,
    onAsk: (MobileWorktreeRow, String) -> Unit,
    onReview: ((MobileWorktreeRow) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("worktree-row-${row.name}")) {
        Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (working) "Working…" else row.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        // Wraps rather than squeezing: at 200% text two labels do not fit one line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.missing) {
                TextButton(onClick = { onAsk(row, MobileWorktreeActionRequest.PRUNE) }, enabled = enabled && row.pruneRefusal == null) { Text("Prune") }
            } else {
                TextButton(onClick = { onAsk(row, MobileWorktreeActionRequest.MERGE) }, enabled = enabled && row.mergeRefusal == null) {
                    Text(baseBranch?.let { "Merge into $it…" } ?: "Merge…")
                }
                TextButton(onClick = { onAsk(row, MobileWorktreeActionRequest.REMOVE) }, enabled = enabled && row.removeRefusal == null) { Text("Remove…") }
                // Absent from a machine that cannot create change requests from the phone. One entry
                // with the desk's three under it: three more buttons per row buried the rows at 200%.
                if (requestNoun != null) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }, enabled = enabled && row.requestRefusal == null, modifier = Modifier.testTag("worktree-request-${row.name}")) {
                            Text(requestNoun.replaceFirstChar(Char::uppercase) + "…")
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            REQUEST_LABELS.forEach { (action, label) ->
                                DropdownMenuItem(text = { Text(label(requestNoun) + "…") }, onClick = { open = false; onAsk(row, action) })
                            }
                        }
                    }
                }
                // Reading, not pushing: a dirty tree or a branch with no commits may still be reviewed.
                if (onReview != null) {
                    TextButton(onClick = { onReview(row) }, enabled = enabled && row.reviewRefusal == null, modifier = Modifier.testTag("worktree-review-${row.name}")) {
                        Text("Review ${requestNoun ?: "change request"}…")
                    }
                }
            }
        }
        // The desk shows these as the disabled entries' tooltips; a phone has no hover, so they are printed.
        listOfNotNull(
            row.pruneRefusal,
            row.mergeRefusal.takeIf { !row.missing },
            row.removeRefusal,
            row.requestRefusal.takeIf { !row.missing && requestNoun != null },
            row.reviewRefusal.takeIf { !row.missing && onReview != null },
        ).distinct().forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The desk's entry labels (`ChangeRequests.label`), for the service's own noun. */
private val REQUEST_LABELS: List<Pair<String, (String) -> String>> = listOf(
    MobileWorktreeActionRequest.REQUEST to { noun -> "Create $noun" },
    MobileWorktreeActionRequest.REQUEST_DRAFT to { noun -> "Create draft $noun" },
    MobileWorktreeActionRequest.REQUEST_STACKED to { noun -> "Create stacked $noun" },
)

@Composable
private fun Confirmation(confirm: WorktreeConfirm, noun: String, onConfirm: () -> Unit, onBack: () -> Unit) {
    val merge = confirm.action == MobileWorktreeActionRequest.MERGE
    val request = REQUEST_LABELS.firstOrNull { it.first == confirm.action }?.second?.invoke(noun)
    Text(
        when {
            request != null -> request.replaceFirstChar(Char::uppercase)
            merge -> "Merge worktree branch"
            else -> "Remove worktree"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(worktreeConfirmation(confirm, noun), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).testTag("worktree-confirm-text"))
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onBack) { Text("Cancel") }
        Button(
            onClick = onConfirm,
            colors = if (merge || request != null) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.testTag("worktree-confirm"),
        ) { Text(if (request != null) "Push" else if (merge) "Merge" else "Remove") }
    }
}

/**
 * The desk's dialog text (`WorktreeActions.confirmMerge`/`confirmRemove`), "here" being the machine;
 * a change request's is its menu entry's tooltip (`WorktreeActions.prTooltip`) as a question.
 */
fun worktreeConfirmation(confirm: WorktreeConfirm, noun: String = "pull request"): String {
    val row = confirm.row
    return when (confirm.action) {
        MobileWorktreeActionRequest.REQUEST ->
            "Push ${row.branch} and open a $noun, or update the one it already has?"
        MobileWorktreeActionRequest.REQUEST_DRAFT ->
            "Push ${row.branch} and open a $noun that is not ready for review yet, or turn its open one into a draft?"
        MobileWorktreeActionRequest.REQUEST_STACKED ->
            "Push ${row.branch} and open a $noun against the worktree branch it was cut from, not ${confirm.baseBranch ?: "the main branch"}? That branch is pushed too."
        else -> mergeOrRemoveConfirmation(confirm)
    }
}

private fun mergeOrRemoveConfirmation(confirm: WorktreeConfirm): String {
    val row = confirm.row
    return if (confirm.action == MobileWorktreeActionRequest.MERGE) {
        "Merge ${row.branch} into ${confirm.baseBranch} in the machine's checkout?\n\n" +
            "${row.ahead} commit(s) will be merged. Conflicts stop the merge and are left for you to resolve on the machine."
    } else {
        row.discardWarning?.let { "$it\n\nRemove ${row.name} and discard them?" }
            ?: "Remove the worktree ${row.name}?\n\nIts branch ${row.branch.orEmpty()} is kept."
    }
}
