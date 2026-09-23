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

/** What the reader has typed into one review and not yet had accepted: a reply per thread, and the review body. */
data class ReviewDrafts(val replies: Map<String, String> = emptyMap(), val verdict: String = "")

/**
 * The review sheet for one worktree's branch: the machine's reading of its open request, the verb
 * running ([working] is a thread id or [VERDICT]), why the last one did not land, and the drafts.
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
    }
}

/**
 * A Manage worktrees row's "Review pull request…" on the phone (M4, P22): the desk dialog's reply,
 * Resolve / Reopen and verdicts, run by the machine against the request the reader was looking at.
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
        }
    }

    fun editReply(threadId: String, text: String) = editDrafts { it.copy(replies = it.replies + (threadId to text)) }

    fun editVerdict(text: String) = editDrafts { it.copy(verdict = text) }

    fun reply(threadId: String) {
        val text = _sheet.value?.drafts?.replies?.get(threadId).orEmpty()
        if (text.isBlank()) return
        run(threadId, MobileChangeRequestReviewRequest.REPLY, threadId, text)
    }

    fun setResolved(threadId: String, resolved: Boolean) = run(
        threadId,
        if (resolved) MobileChangeRequestReviewRequest.RESOLVE else MobileChangeRequestReviewRequest.REOPEN,
        threadId,
    )

    /** [action] is one of [MobileChangeRequestReviewRequest.VERDICTS], with the review body as typed. */
    fun submit(action: String) = run(ReviewSheet.VERDICT, action, null, _sheet.value?.drafts?.verdict.orEmpty())

    private fun run(working: String, action: String, threadId: String?, body: String = "") {
        val sheet = _sheet.value?.takeIf { it.working == null && running == null } ?: return
        val requestId = sheet.review?.requestId ?: return
        val client = client() ?: return
        val asked = generation()
        val fresh = MobileChangeRequestReviewRequest(sheet.projectPath, sheet.path, sheet.branch, requestId, action, threadId, body, "")
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
            if (posted) {
                // Only the text that was posted: a draft edited meanwhile belongs to the next send.
                editDrafts(sheet.path, sheet.branch) { d ->
                    when {
                        threadId != null && action == MobileChangeRequestReviewRequest.REPLY && d.replies[threadId] == body ->
                            d.copy(replies = d.replies - threadId)
                        threadId == null && d.verdict == body -> d.copy(verdict = "")
                        else -> d
                    }
                }
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
