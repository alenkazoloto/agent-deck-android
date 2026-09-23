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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import com.github.claudeagents.core.mobile.MobileChangeRequestComment
import com.github.claudeagents.core.mobile.MobileChangeRequestReview
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
    /**
     * The dialog's other verbs — reaction, comment and request edits, reviewers, labels. Null from a
     * machine without [com.github.claudeagents.core.mobile.MobileProtocol.Capability.CHANGE_REQUEST_EDITS].
     */
    val edits: ReviewEditActions? = null,
)

/** Reaction, reviewers, labels and the two editors, all through `ChangeRequestReviewFlow`. */
class ReviewEditActions(
    val onReact: (String, String) -> Unit,
    val onReviewersText: (String) -> Unit,
    val onRequestReviewers: () -> Unit,
    val onLabelsText: (String) -> Unit,
    val onLabels: (Boolean) -> Unit,
    val onEditRequest: () -> Unit,
    val onRequestTitle: (String) -> Unit,
    val onRequestBody: (String) -> Unit,
    val onCancelRequest: () -> Unit,
    val onSaveRequest: () -> Unit,
    val onEditComment: (String, String) -> Unit,
    val onCommentText: (String, String) -> Unit,
    val onCancelComment: (String) -> Unit,
    val onSaveComment: (String) -> Unit,
)

/**
 * The desk's review dialog as a sheet over Manage worktrees: the request's heading and link, every
 * thread with its comments, a reply field under each and Resolve / Reopen, then reviewers, labels,
 * the review body and the three verdicts. The pencil beside the title and on each comment opens an
 * editor in place, as the desk's does. What this service cannot do from here stays visible,
 * disabled, with its sentence printed — a phone has no tooltip to carry it.
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
            val editing = sheet.drafts.request.takeIf { actions.edits != null && review?.requestId != null }
            if (editing != null && actions.edits != null) {
                RequestEditor(editing, sheet, noun, actions.edits)
            } else if (review?.requestId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(review.title.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 4.dp))
                    actions.edits?.let { edits ->
                        DeckIconButton(
                            label = "Edit the title and description",
                            icon = Icons.Filled.Edit,
                            onClick = edits.onEditRequest,
                            enabled = sheet.working == null && review.editRefusal == null,
                            modifier = Modifier.testTag("review-edit-request"),
                        )
                    }
                }
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
                review.editRefusal?.takeIf { actions.edits != null }?.let { Refusal(it) }
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
                        ThreadView(thread, sheet, review, actions)
                    }
                    review.reactRefusal?.takeIf { actions.edits != null && review.threads.isNotEmpty() }?.let { Refusal(it) }
                    actions.edits?.let { edits ->
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                        PeopleAndLabels(sheet, review, edits)
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
                    review.verdictRefusals.values.distinct().forEach { Refusal(it) }
                }
            }
            sheet.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp).testTag("review-error"))
            }
        }
    }
}

/** The desk's in-place Markdown editor for the request's own title and description. */
@Composable
private fun RequestEditor(edit: dev.agentdeck.companion.data.RequestEdit, sheet: ReviewSheet, noun: String, edits: ReviewEditActions) {
    val busy = sheet.working != null
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("review-request-editor")) {
        OutlinedTextField(
            value = edit.title,
            onValueChange = edits.onRequestTitle,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("review-request-title"),
        )
        OutlinedTextField(
            value = edit.body,
            onValueChange = edits.onRequestBody,
            label = { Text("Description (Markdown)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("review-request-body"),
        )
        if (edit.rebased) {
            Changed(listOf(edit.baseTitle, edit.baseBody).filter(String::isNotBlank).joinToString("\n\n"), "review-request-changed")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            TextButton(onClick = edits.onCancelRequest, enabled = !busy) { Text("Cancel") }
            TextButton(
                onClick = edits.onSaveRequest,
                enabled = !busy && edit.title.isNotBlank(),
                modifier = Modifier.testTag("review-request-save"),
            ) { Text(if (sheet.working == ReviewSheet.REQUEST) "Saving…" else "Save the ${noun}") }
        }
    }
}

/** The desk footer's Reviewers and Labels rows: typed names, never a guessed list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleAndLabels(sheet: ReviewSheet, review: MobileChangeRequestReview, edits: ReviewEditActions) {
    val busy = sheet.working != null
    Text("Reviewers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (review.reviewersRefusal == null) {
        OutlinedTextField(
            value = sheet.drafts.reviewers,
            onValueChange = edits.onReviewersText,
            placeholder = { Text("Names, separated by commas") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("review-reviewers-text"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = edits.onRequestReviewers,
                enabled = !busy && sheet.drafts.reviewers.isNotBlank(),
                modifier = Modifier.testTag("review-reviewers-request"),
            ) { Text(if (sheet.working == ReviewSheet.REVIEWERS) "Requesting…" else "Request review") }
        }
    } else {
        Refusal(review.reviewersRefusal)
    }
    Text("Labels", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (review.labelRefusal == null) {
        val typed = sheet.drafts.labels
        OutlinedTextField(
            value = typed,
            onValueChange = edits.onLabelsText,
            placeholder = { Text("Labels, separated by commas") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("review-labels-text"),
        )
        // The desk field's completion: the repository's labels matching the name being typed.
        val suggestions = labelSuggestions(typed, review.repoLabels)
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { label ->
                    SuggestionChip(
                        onClick = { edits.onLabelsText(completeLabel(typed, label)) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("review-label-suggestion-$label"),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            TextButton(onClick = { edits.onLabels(false) }, enabled = !busy && typed.isNotBlank(), modifier = Modifier.testTag("review-labels-remove")) {
                Text("Remove")
            }
            TextButton(onClick = { edits.onLabels(true) }, enabled = !busy && typed.isNotBlank(), modifier = Modifier.testTag("review-labels-apply")) {
                Text(if (sheet.working == ReviewSheet.LABELS) "Applying…" else "Apply")
            }
        }
    } else {
        Refusal(review.labelRefusal)
    }
}

/** Up to six repository labels that start with the name after the last comma, not yet typed. */
internal fun labelSuggestions(typed: String, repoLabels: List<String>): List<String> {
    val done = typed.split(',').dropLast(1).map { it.trim() }.toSet()
    val prefix = typed.substringAfterLast(',').trim()
    return repoLabels.filter { it !in done && it != prefix && it.startsWith(prefix, ignoreCase = true) }.take(6)
}

/** [typed] with its last, partial name replaced by [label]. */
internal fun completeLabel(typed: String, label: String): String {
    val head = typed.substringBeforeLast(',', missingDelimiterValue = "").trim()
    return if (head.isEmpty()) label else "$head, $label"
}

/** Text someone changed after the editor opened: what it says now, so a second Save replaces it knowingly. */
@Composable
private fun Changed(now: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(tag)) {
        Refusal("Changed since you opened it. It now says:")
        SelectionContainer { Text(now, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Refusal(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ThreadView(
    thread: MobileChangeRequestThread,
    sheet: ReviewSheet,
    review: MobileChangeRequestReview,
    actions: ReviewActions,
) {
    val replyRefusal = review.replyRefusal
    val resolveRefusal = review.resolveRefusal
    val busy = sheet.working != null
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("review-thread-${thread.id}")) {
        Text(thread.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        thread.comments.forEach { comment -> CommentView(thread, comment, sheet, review, actions.edits) }
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
        listOfNotNull(replyRefusal, resolveRefusal.takeIf { thread.resolvable }).distinct().forEach { Refusal(it) }
    }
}

/**
 * One comment: author and reaction count, the thumbs-up and the pencil, then its text — or, while
 * the pencil is open, its editor. A comment the service gave no id cannot be reacted to or edited.
 */
@Composable
private fun CommentView(
    thread: MobileChangeRequestThread,
    comment: MobileChangeRequestComment,
    sheet: ReviewSheet,
    review: MobileChangeRequestReview,
    edits: ReviewEditActions?,
) {
    val busy = sheet.working != null
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(comment.author ?: "Someone", "👍 ${comment.reactions}".takeIf { comment.reactions > 0 }).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        // Shown only where they can work: the service's no to a reaction is printed once under the
        // threads, and a pencil on every comment someone else wrote would be a row of dead controls.
        if (edits != null && comment.id.isNotEmpty() && review.reactRefusal == null) {
            DeckIconButton(
                label = "React with a thumbs up",
                icon = Icons.Filled.ThumbUp,
                onClick = { edits.onReact(thread.id, comment.id) },
                enabled = !busy,
                modifier = Modifier.testTag("review-react-${comment.id}"),
            )
        }
        if (edits != null && comment.id.isNotEmpty() && comment.editRefusal == null) {
            DeckIconButton(
                label = "Edit this comment",
                icon = Icons.Filled.Edit,
                onClick = { edits.onEditComment(thread.id, comment.id) },
                enabled = !busy,
                modifier = Modifier.testTag("review-edit-${comment.id}"),
            )
        }
    }
    val editing = sheet.drafts.comments[comment.id]?.takeIf { edits != null && comment.id.isNotEmpty() }
    if (editing != null && edits != null) {
        OutlinedTextField(
            value = editing.body,
            onValueChange = { edits.onCommentText(comment.id, it) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("review-comment-editor-${comment.id}"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            TextButton(onClick = { edits.onCancelComment(comment.id) }, enabled = !busy) { Text("Cancel") }
            TextButton(
                onClick = { edits.onSaveComment(comment.id) },
                enabled = !busy && editing.body.isNotBlank(),
                modifier = Modifier.testTag("review-comment-save-${comment.id}"),
            ) { Text(if (sheet.working == ReviewSheet.commentKey(comment.id)) "Saving…" else "Save") }
        }
        if (editing.rebased) Changed(editing.base, "review-comment-changed-${comment.id}")
    } else {
        SelectionContainer { Text(comment.body, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** The desk dialog's verdict buttons. */
private val VERDICT_LABELS = listOf(
    MobileChangeRequestReviewRequest.APPROVE to "Approve",
    MobileChangeRequestReviewRequest.REQUEST_CHANGES to "Request changes",
    MobileChangeRequestReviewRequest.COMMENT to "Comment",
)
