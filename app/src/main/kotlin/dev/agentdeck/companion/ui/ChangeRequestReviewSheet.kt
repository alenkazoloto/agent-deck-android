package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileChangeRequestReviewRequest
import com.github.claudeagents.core.mobile.MobileChangeRequestThread
import dev.agentdeck.companion.data.ReviewSheet

/** What the review sheet can ask of [dev.agentdeck.companion.data.ChangeRequestReviewFlow]. */
class ReviewActions(
    val onReplyText: (String, String) -> Unit,
    val onReply: (String) -> Unit,
    val onResolve: (String, Boolean) -> Unit,
    val onVerdictText: (String) -> Unit,
    val onSubmit: (String) -> Unit,
    val onReload: () -> Unit,
    val onBack: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The desk's review dialog as a sheet over Manage worktrees: the request's heading and link, every
 * thread with its comments, a reply field under each and Resolve / Reopen, then the review body and
 * the three verdicts. What this service cannot do from here stays visible, disabled, with its
 * sentence printed — a phone has no tooltip to carry it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChangeRequestReviewView(sheet: ReviewSheet, actions: ReviewActions) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("review-sheet"),
        ) {
            val review = sheet.review
            val noun = review?.noun ?: "pull request"
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeckIconButton(label = "Back to worktrees", icon = Icons.Filled.ArrowBack, onClick = actions.onBack)
                Text(
                    review?.requestId?.let { "Review ${noun.replaceFirstChar(Char::uppercase)} #$it" } ?: "Review ${sheet.branch}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (review?.requestId != null) {
                review.title?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp)) }
                Text(
                    listOfNotNull(review.base?.let { "${sheet.branch} → $it" } ?: sheet.branch, "draft".takeIf { review.draft }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                review.url?.let { url ->
                    val uri = LocalUriHandler.current
                    TextButton(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.testTag("review-link")) {
                        Text("Open in browser")
                    }
                }
            }
            if (review == null || sheet.reloading || sheet.working != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val refused = review?.refused
            when {
                review == null -> Unit
                refused != null -> {
                    Text(refused, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp).testTag("review-refused"))
                    TextButton(onClick = actions.onReload) { Text("Try again") }
                }
                else -> {
                    if (review.threads.isEmpty()) review.emptyText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    }
                    review.threads.forEach { thread ->
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                        ThreadView(thread, sheet, review.replyRefusal, review.resolveRefusal, actions)
                    }
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    Text("Your review", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        value = sheet.drafts.verdict,
                        onValueChange = actions.onVerdictText,
                        placeholder = { Text("Say what you think (Markdown)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("review-verdict-text"),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        VERDICT_LABELS.forEach { (action, label) ->
                            OutlinedButton(
                                onClick = { actions.onSubmit(action) },
                                enabled = sheet.working == null && action !in review.verdictRefusals,
                                modifier = Modifier.testTag("review-verdict-$action"),
                            ) { Text(label) }
                        }
                    }
                    review.verdictRefusals.values.distinct().forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            sheet.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp).testTag("review-error"))
            }
        }
    }
}

@Composable
private fun ThreadView(
    thread: MobileChangeRequestThread,
    sheet: ReviewSheet,
    replyRefusal: String?,
    resolveRefusal: String?,
    actions: ReviewActions,
) {
    val busy = sheet.working != null
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("review-thread-${thread.id}")) {
        Text(thread.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column {
                thread.comments.forEach { comment ->
                    Text(comment.author ?: "Someone", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp))
                    Text(comment.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (replyRefusal == null) {
            val text = sheet.drafts.replies[thread.id].orEmpty()
            OutlinedTextField(
                value = text,
                onValueChange = { actions.onReplyText(thread.id, it) },
                placeholder = { Text("Reply") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("review-reply-text-${thread.id}"),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            if (thread.resolvable) {
                TextButton(
                    onClick = { actions.onResolve(thread.id, !thread.resolved) },
                    enabled = !busy && resolveRefusal == null,
                    modifier = Modifier.testTag("review-resolve-${thread.id}"),
                ) { Text(if (thread.resolved) "Reopen" else "Resolve") }
            }
            if (replyRefusal == null) {
                TextButton(
                    onClick = { actions.onReply(thread.id) },
                    enabled = !busy && sheet.drafts.replies[thread.id].orEmpty().isNotBlank(),
                    modifier = Modifier.testTag("review-reply-${thread.id}"),
                ) { Text(if (sheet.working == thread.id) "Sending…" else "Reply") }
            }
        }
        listOfNotNull(replyRefusal, resolveRefusal.takeIf { thread.resolvable }).distinct().forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The desk dialog's verdict buttons. */
private val VERDICT_LABELS = listOf(
    MobileChangeRequestReviewRequest.APPROVE to "Approve",
    MobileChangeRequestReviewRequest.REQUEST_CHANGES to "Request changes",
    MobileChangeRequestReviewRequest.COMMENT to "Comment",
)
