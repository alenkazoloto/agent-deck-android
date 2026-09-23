package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileChangeRequestReview
import com.github.claudeagents.core.mobile.MobileChangeRequestReviewRequest
import com.github.claudeagents.core.mobile.MobileWorktreeActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * What the reader has typed into one review and not yet had accepted: a reply per thread, the review
 * body, the reviewers and labels fields, and an open title/description or comment editor.
 */
data class ReviewDrafts(
    val replies: Map<String, String> = emptyMap(),
    val verdict: String = "",
    val reviewers: String = "",
    val labels: String = "",
    val request: RequestEdit? = null,
    /** Open comment editors by comment id: a second pencil never discards the first one's text. */
    val comments: Map<String, CommentEdit> = emptyMap(),
)

/**
 * The request's title and description as edited, and the text the editor opened on ([baseTitle], [baseBody]).
 * [recheck] asks the next reading to compare that base with the service's text, after a refused save;
 * [rebased] says it moved — the base is now the service's current text, shown beside the edit, so a
 * second Save replaces it knowingly instead of being refused for ever.
 */
data class RequestEdit(
    val title: String,
    val body: String,
    val baseTitle: String,
    val baseBody: String,
    val recheck: Boolean = false,
    val rebased: Boolean = false,
)

/** One comment as edited; [base] is what it said when the editor opened; [recheck] and [rebased] as on [RequestEdit]. */
data class CommentEdit(
    val threadId: String,
    val commentId: String,
    val body: String,
    val base: String,
    val recheck: Boolean = false,
    val rebased: Boolean = false,
)

/**
 * The review sheet for one worktree's branch: the machine's reading of its open request, the verb
 * running ([working] is a thread id, [commentKey], [VERDICT], [REVIEWERS], [LABELS] or [REQUEST]),
 * why the last one did not land, and the drafts.
 */
data class ReviewSheet(
    val projectPath: String,
    val path: String,
    val branch: String,
    val review: MobileChangeRequestReview? = null,
    val working: String? = null,
    val error: String? = null,
    val drafts: ReviewDrafts = ReviewDrafts(),
    /** Re-reading after a post: the last reading stays on screen, so the reader keeps their place. */
    val reloading: Boolean = false,
) {
    val loaded: Boolean get() = review != null

    companion object {
        const val VERDICT = "verdict"
        const val REVIEWERS = "reviewers"
        const val LABELS = "labels"
        const val REQUEST = "request"

        fun commentKey(commentId: String) = "comment:$commentId"
    }
}

/**
 * A Manage worktrees row's "Review pull request…" on the phone (M4, P22): the desk dialog's reply,
 * Resolve / Reopen, verdicts, reactions, reviewers, labels and edits of the request and of a comment,
 * run by the machine against the request the reader was looking at. An edit carries the text its
 * editor opened on, and the machine refuses it when the service's text has moved on since.
 * A reply or review is posted once: a verb whose answer never arrived keeps its operation id, so
 * sending the same text again reads the first attempt back. Typed text survives closing the sheet
 * and a refused send, and is cleared only when the machine says it was posted — as the desk's
 * dialog clears a reply field only on success.
 */
class ChangeRequestReviewFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val snack: (String) -> Unit,
    private val machine: () -> String? = { null },
    private val pollMs: Long = WorktreeManageFlow.POLL_MS,
) {
    private var uncertain: MobileChangeRequestReviewRequest? = null

    /**
     * The verb in flight and the sheet key it shows as working. It outlives the sheet: closed and
     * reopened mid-post, the sheet shows it still working rather than offering the same text again.
     */
    private var running: Pair<MobileChangeRequestReviewRequest, String>? = null
    private var reading: Job? = null
    private val drafts = HashMap<List<String?>, ReviewDrafts>()
    private val _sheet = MutableStateFlow<ReviewSheet?>(null)
    val sheet: StateFlow<ReviewSheet?> = _sheet.asStateFlow()

    fun open(projectPath: String, path: String, branch: String) {
        val working = running?.takeIf { (r, _) -> r.path == path && r.branch == branch }?.second
        _sheet.value = ReviewSheet(projectPath, path, branch, working = working, drafts = drafts[key(path, branch)] ?: ReviewDrafts())
        reload()
    }

    fun close() {
        reading?.cancel()
        _sheet.value = null
    }

    fun reload() {
        val sheet = _sheet.value ?: return
        val client = client() ?: return
        val asked = generation()
        reading?.cancel()
        _sheet.value = sheet.copy(reloading = true)
        reading = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.changeRequestReview(sheet.projectPath, sheet.path, sheet.branch) } }
            if (asked != generation() || _sheet.value?.path != sheet.path) return@launch
            val review = outcome.getOrElse { MobileChangeRequestReview(sheet.projectPath, sheet.path, sheet.branch, refused = describe(it)) }
            _sheet.update { it?.copy(review = review, reloading = false) }
            if (review.requestId != null) editDrafts(sheet.path, sheet.branch) { rebased(it, review) }
        }
    }

    fun editReply(threadId: String, text: String) = editDrafts { it.copy(replies = it.replies + (threadId to text)) }

    fun editVerdict(text: String) = editDrafts { it.copy(verdict = text) }

    fun editReviewers(text: String) = editDrafts { it.copy(reviewers = text) }

    fun editLabels(text: String) = editDrafts { it.copy(labels = text) }

    fun reply(threadId: String) {
        val text = _sheet.value?.drafts?.replies?.get(threadId).orEmpty()
        if (text.isBlank()) return
        run(threadId, fields(MobileChangeRequestReviewRequest.REPLY, threadId = threadId, body = text)) { d ->
            if (d.replies[threadId] == text) d.copy(replies = d.replies - threadId) else d
        }
    }

    fun setResolved(threadId: String, resolved: Boolean) = run(
        threadId,
        fields(if (resolved) MobileChangeRequestReviewRequest.RESOLVE else MobileChangeRequestReviewRequest.REOPEN, threadId = threadId),
    )

    /** [action] is one of [MobileChangeRequestReviewRequest.VERDICTS], with the review body as typed. */
    fun submit(action: String) {
        val text = _sheet.value?.drafts?.verdict.orEmpty()
        run(ReviewSheet.VERDICT, fields(action, body = text)) { d -> if (d.verdict == text) d.copy(verdict = "") else d }
    }

    fun react(threadId: String, commentId: String) = run(
        ReviewSheet.commentKey(commentId),
        fields(MobileChangeRequestReviewRequest.REACT, threadId = threadId, commentId = commentId),
    )

    fun requestReviewers() {
        val text = _sheet.value?.drafts?.reviewers.orEmpty()
        if (text.isBlank()) return
        run(ReviewSheet.REVIEWERS, fields(MobileChangeRequestReviewRequest.REQUEST_REVIEWERS, body = text)) { d ->
            if (d.reviewers == text) d.copy(reviewers = "") else d
        }
    }

    /** Apply ([add]) or remove the labels typed. */
    fun changeLabels(add: Boolean) {
        val text = _sheet.value?.drafts?.labels.orEmpty()
        if (text.isBlank()) return
        val action = if (add) MobileChangeRequestReviewRequest.ADD_LABELS else MobileChangeRequestReviewRequest.REMOVE_LABELS
        run(ReviewSheet.LABELS, fields(action, body = text)) { d -> if (d.labels == text) d.copy(labels = "") else d }
    }

    /** Opens the title/description editor on the request as last read — or on the edit already typed. */
    fun startRequestEdit() {
        val review = _sheet.value?.review ?: return
        val title = review.title.orEmpty()
        val body = review.body.orEmpty()
        editDrafts { d -> if (d.request != null) d else d.copy(request = RequestEdit(title, body, title, body)) }
    }

    fun editRequestTitle(text: String) = editDrafts { d -> d.copy(request = d.request?.copy(title = text)) }

    fun editRequestBody(text: String) = editDrafts { d -> d.copy(request = d.request?.copy(body = text)) }

    fun cancelRequestEdit() = editDrafts { it.copy(request = null) }

    fun saveRequest() {
        val edit = _sheet.value?.drafts?.request ?: return
        val request = fields(MobileChangeRequestReviewRequest.EDIT_REQUEST, body = edit.body)
            .copy(title = edit.title, baseTitle = edit.baseTitle, baseBody = edit.baseBody)
        run(ReviewSheet.REQUEST, request) { d -> if (d.request == edit) d.copy(request = null) else d }
    }

    /** Opens one comment's editor on what it says — or on the edit of it already typed. */
    fun startCommentEdit(threadId: String, commentId: String) {
        val comment = _sheet.value?.review?.threads?.firstOrNull { it.id == threadId }?.comments?.firstOrNull { it.id == commentId } ?: return
        editDrafts { d ->
            if (commentId in d.comments) d else d.copy(comments = d.comments + (commentId to CommentEdit(threadId, commentId, comment.body, comment.body)))
        }
    }

    fun editCommentText(commentId: String, text: String) = editDrafts { d ->
        d.comments[commentId]?.let { d.copy(comments = d.comments + (commentId to it.copy(body = text))) } ?: d
    }

    fun cancelCommentEdit(commentId: String) = editDrafts { it.copy(comments = it.comments - commentId) }

    fun saveComment(commentId: String) {
        val edit = _sheet.value?.drafts?.comments?.get(commentId) ?: return
        val request = fields(MobileChangeRequestReviewRequest.EDIT_COMMENT, threadId = edit.threadId, body = edit.body, commentId = edit.commentId)
            .copy(baseBody = edit.base)
        run(ReviewSheet.commentKey(edit.commentId), request) { d ->
            if (d.comments[commentId] == edit) d.copy(comments = d.comments - commentId) else d
        }
    }

    /**
     * After a refused save, the next reading moves an edit's base to the service's current text when it
     * differs. The machine refuses an edit of text that changed so the reader sees the change first; once
     * the sheet shows it beside their edit, Save means "replace that".
     */
    private fun rebased(d: ReviewDrafts, review: MobileChangeRequestReview): ReviewDrafts {
        val request = d.request?.takeIf { it.recheck }?.let { edit ->
            val title = review.title.orEmpty()
            val body = review.body.orEmpty()
            if (title.trim() == edit.baseTitle.trim() && body.trim() == edit.baseBody.trim()) edit.copy(recheck = false)
            else edit.copy(baseTitle = title, baseBody = body, recheck = false, rebased = true)
        } ?: d.request
        val comments = d.comments.mapValues { (id, edit) ->
            if (!edit.recheck) return@mapValues edit
            val now = review.threads.firstOrNull { it.id == edit.threadId }?.comments?.firstOrNull { it.id == id }?.body
                ?: return@mapValues edit.copy(recheck = false)
            if (now.trim() == edit.base.trim()) edit.copy(recheck = false) else edit.copy(base = now, recheck = false, rebased = true)
        }
        return d.copy(request = request, comments = comments)
    }

    /** The verb's fields on the sheet's request; [run] fills in the request id and operation id. */
    private fun fields(action: String, threadId: String? = null, body: String = "", commentId: String? = null) =
        MobileChangeRequestReviewRequest("", "", "", "", action, threadId, body, "", commentId = commentId)

    /**
     * Posts [verb] against the request the sheet is reading. [consumed] clears the draft it carried —
     * only once the machine says it landed, and only if that draft was not edited meanwhile.
     */
    private fun run(working: String, verb: MobileChangeRequestReviewRequest, consumed: (ReviewDrafts) -> ReviewDrafts = { it }) {
        val sheet = _sheet.value?.takeIf { it.working == null && running == null } ?: return
        val requestId = sheet.review?.requestId ?: return
        val client = client() ?: return
        val asked = generation()
        val fresh = verb.copy(projectPath = sheet.projectPath, path = sheet.path, branch = sheet.branch, requestId = requestId)
        val request = fresh.copy(operationId = uncertain?.takeIf { it.copy(operationId = "") == fresh }?.operationId ?: UUID.randomUUID().toString())
        _sheet.value = sheet.copy(working = working, error = null)
        running = request to working
        // Held from the first attempt, so a send of the same text before this one settles reads it back.
        uncertain = request
        scope.launch {
            var outcome: Result<MobileWorktreeActionResult>
            var waited = 0L
            var acknowledged = false
            while (true) {
                outcome = withContext(Dispatchers.IO) { runCatching { client.changeRequestReviewAction(request) } }
                if (asked != generation()) { running = null; return@launch }
                val pending = outcome.getOrNull()?.state == MobileWorktreeActionResult.WORKING
                acknowledged = acknowledged || pending
                if (!pending || waited >= WorktreeManageFlow.MAX_WAIT_MS) break
                delay(pollMs)
                waited += pollMs
            }
            var posted = false
            val error = outcome.fold(
                onSuccess = { result ->
                    when {
                        result.done -> { uncertain = null; posted = true; snack(result.message); null }
                        result.state == MobileWorktreeActionResult.WORKING -> { uncertain = request; STILL_POSTING }
                        else -> { uncertain = null; result.message.ifBlank { "The service refused it." } }
                    }
                },
                // Once the machine said "working", a later refusal leaves that post running.
                onFailure = { uncertain = if (it is BridgeRefusal && !acknowledged) null else request; describe(it) },
            )
            running = null
            _sheet.update { if (it != null && it.path == sheet.path && it.branch == sheet.branch) it.copy(working = null, error = error) else it }
            // A refused edit may be of text that changed: the next reading shows the change beside it.
            val refusedEdit = !posted && error != null && error != STILL_POSTING &&
                (request.action == MobileChangeRequestReviewRequest.EDIT_REQUEST || request.action == MobileChangeRequestReviewRequest.EDIT_COMMENT)
            if (refusedEdit) {
                editDrafts(sheet.path, sheet.branch) { d ->
                    when (request.action) {
                        MobileChangeRequestReviewRequest.EDIT_REQUEST -> d.copy(request = d.request?.copy(recheck = true))
                        else -> request.commentId?.let { id -> d.comments[id]?.let { d.copy(comments = d.comments + (id to it.copy(recheck = true))) } } ?: d
                    }
                }
                if (_sheet.value?.let { it.path == sheet.path && it.branch == sheet.branch } == true) reload()
            }
            if (posted) {
                // Only the text that was posted: a draft edited meanwhile belongs to the next send.
                editDrafts(sheet.path, sheet.branch, consumed)
                if (_sheet.value?.let { it.path == sheet.path && it.branch == sheet.branch } == true) reload()
            }
        }
    }

    private fun editDrafts(change: (ReviewDrafts) -> ReviewDrafts) {
        val sheet = _sheet.value ?: return
        editDrafts(sheet.path, sheet.branch, change)
    }

    private fun editDrafts(path: String, branch: String, change: (ReviewDrafts) -> ReviewDrafts) {
        val key = key(path, branch)
        val next = change(drafts[key] ?: ReviewDrafts())
        drafts[key] = next
        _sheet.update { if (it != null && it.path == path && it.branch == branch) it.copy(drafts = next) else it }
    }

    /** Drafts belong to one machine's worktree branch; another machine's same path is another request. */
    private fun key(path: String, branch: String): List<String?> = listOf(machine(), path, branch)

    /** A different machine: its request and a pending id mean nothing there. Its drafts wait under its own key. */
    fun forget() {
        uncertain = null
        running = null
        close()
    }

    companion object {
        const val STILL_POSTING =
            "The machine is still posting this. Send it again to check on it; it is not posted twice."
    }
}
