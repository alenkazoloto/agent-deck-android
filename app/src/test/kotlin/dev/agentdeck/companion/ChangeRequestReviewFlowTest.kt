package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileChangeRequestComment
import com.github.claudeagents.core.mobile.MobileChangeRequestFile
import com.github.claudeagents.core.mobile.MobileChangeRequestStackEntry
import com.github.claudeagents.core.mobile.MobileChangeRequestReview
import com.github.claudeagents.core.mobile.MobileChangeRequestReviewRequest
import com.github.claudeagents.core.mobile.MobileChangeRequestThread
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileWorktreeActionResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.ChangeRequestReviewFlow
import dev.agentdeck.companion.data.CommentEdit
import dev.agentdeck.companion.data.RequestEdit
import dev.agentdeck.companion.ui.completeLabel
import dev.agentdeck.companion.ui.labelSuggestions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * A worktree's change-request review (M4, P22): a reply goes to the thread it was typed under on the
 * request the reader was looking at, is posted once however its answer was lost, and its text stays
 * until the machine says it was posted — across a refusal and closing the sheet, but never onto
 * another machine's request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangeRequestReviewFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<MobileChangeRequestReviewRequest>()
    private val snacks = CopyOnWriteArrayList<String>()
    private val reads = AtomicInteger()
    private val queries = CopyOnWriteArrayList<String>()
    /** A result, a [MobileRefusal] the machine answers with, or null — thrown, as a dropped connection is. */
    private val answers = ArrayDeque<Any?>()
    private var machine = "studio"
    /** While set, a post with no queued answer is still working on the machine. */
    @Volatile private var holding = false
    /** What the machine reads back; a test changes it to be someone else's edit on the service. */
    @Volatile private var current = REVIEW
    private val flow = ChangeRequestReviewFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, { snacks += it }, { machine }, pollMs = 1)

    @After fun tearDown() = scope.cancel()

    @Test fun `a reply goes to its thread on the request being read, and its text clears once posted`() {
        opened()
        flow.editReply("t1", "on it")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Replied.")
        val before = reads.get()
        flow.reply("t1")
        eventually { snacks.firstOrNull() }

        val request = sent.single()
        assertEquals(MobileChangeRequestReviewRequest.REPLY, request.action)
        assertEquals("t1", request.threadId)
        assertEquals("on it", request.body)
        assertEquals("the request the reader was looking at", "7", request.requestId)
        assertEquals(PATH, request.path)
        assertEquals("feature", request.branch)
        assertEquals("Replied.", snacks.single())
        eventually { flow.sheet.value?.takeIf { it.working == null && it.drafts.replies["t1"] == null } }
        eventually { reads.get().takeIf { it > before } }
    }

    @Test fun `a refused reply keeps its text and says why`() {
        opened()
        flow.editReply("t1", "on it")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.FAILED, "That thread is no longer on the pull request. Open the review again.")
        flow.reply("t1")
        val error = eventually { flow.sheet.value?.error }

        assertEquals("That thread is no longer on the pull request. Open the review again.", error)
        assertEquals("on it", flow.sheet.value!!.drafts.replies["t1"])
        assertTrue(snacks.isEmpty())
    }

    @Test fun `a reply whose answer was lost is sent again under the same operation, and edited text is a new one`() {
        opened()
        flow.editReply("t1", "on it")
        answers += null
        flow.reply("t1")
        eventually { flow.sheet.value?.error }
        assertEquals("on it", flow.sheet.value!!.drafts.replies["t1"])

        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Replied.")
        flow.reply("t1")
        eventually { snacks.firstOrNull() }
        assertEquals("the retry reads the first attempt back instead of posting twice", 1, sent.map { it.operationId }.distinct().size)

        flow.editReply("t1", "something else")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Replied.")
        flow.reply("t1")
        eventually { snacks.getOrNull(1) }
        assertNotEquals(sent[0].operationId, sent[2].operationId)
    }

    @Test fun `a post still working when the sheet closes shows working on reopen and is not sent again`() {
        opened()
        flow.editReply("t1", "on it")
        holding = true
        flow.reply("t1")
        eventually { sent.firstOrNull() }

        flow.close()
        opened()
        assertEquals("the reopened sheet shows the post still running", "t1", flow.sheet.value!!.working)
        flow.reply("t1")

        synchronized(answers) { answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Replied.") }
        holding = false
        eventually { snacks.firstOrNull() }
        assertEquals("one operation however often it was asked about", 1, sent.map { it.operationId }.distinct().size)
        eventually { flow.sheet.value?.takeIf { it.working == null && it.drafts.replies["t1"] == null } }
    }

    @Test fun `typed text survives closing the sheet, and stays with its own machine`() {
        opened()
        flow.editReply("t1", "on it")
        flow.editVerdict("Ship it.")
        flow.close()
        opened()
        assertEquals("on it", flow.sheet.value!!.drafts.replies["t1"])
        assertEquals("Ship it.", flow.sheet.value!!.drafts.verdict)

        flow.forget()
        machine = "laptop"
        opened()
        assertNull("another machine's request at the same path has no draft", flow.sheet.value!!.drafts.replies["t1"])
        machine = "studio"
        opened()
        assertEquals("on it", flow.sheet.value!!.drafts.replies["t1"])
    }

    @Test fun `a verdict carries the review body, and resolve names its thread`() {
        opened()
        flow.editVerdict("Ship it.")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Approved the pull request.")
        flow.submit(MobileChangeRequestReviewRequest.APPROVE)
        eventually { snacks.firstOrNull() }
        assertEquals(MobileChangeRequestReviewRequest.APPROVE, sent.single().action)
        assertEquals("Ship it.", sent.single().body)
        assertNull(sent.single().threadId)
        eventually { flow.sheet.value?.takeIf { it.working == null && it.drafts.verdict.isEmpty() } }

        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Resolved the thread.")
        flow.setResolved("t1", true)
        eventually { snacks.getOrNull(1) }
        assertEquals(MobileChangeRequestReviewRequest.RESOLVE, sent[1].action)
        assertEquals("t1", sent[1].threadId)
    }

    @Test fun `a reaction names its comment, and reviewers and labels clear once they landed`() {
        opened()
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Reacted.")
        flow.react("t1", "c1")
        eventually { snacks.firstOrNull() }
        assertEquals(MobileChangeRequestReviewRequest.REACT, sent[0].action)
        assertEquals("t1", sent[0].threadId)
        assertEquals("c1", sent[0].commentId)

        flow.editReviewers("ana, bo")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.FAILED, "Could not resolve to a User with the login of 'bo'.")
        flow.requestReviewers()
        eventually { flow.sheet.value?.error }
        assertEquals("a refused request keeps the names typed", "ana, bo", flow.sheet.value!!.drafts.reviewers)
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Requested 2 reviewer(s).")
        flow.requestReviewers()
        eventually { flow.sheet.value?.takeIf { it.working == null && it.drafts.reviewers.isEmpty() } }
        assertEquals(MobileChangeRequestReviewRequest.REQUEST_REVIEWERS, sent[2].action)
        assertEquals("ana, bo", sent[2].body)

        flow.editLabels("bug")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Removed 1 label(s).")
        flow.changeLabels(add = false)
        eventually { flow.sheet.value?.takeIf { it.working == null && it.drafts.labels.isEmpty() } }
        assertEquals(MobileChangeRequestReviewRequest.REMOVE_LABELS, sent[3].action)
        assertEquals("bug", sent[3].body)
    }

    /**
     * An edit carries the text its editor opened on, so the machine can refuse it when someone has
     * changed that text since. The refusal keeps the edit, and the next reading moves its base to the
     * text now on the service — shown beside it — so Save again replaces that instead of being refused
     * for ever.
     */
    @Test fun `a request edit refused as stale is rebased on the text read, and saves on the second try`() {
        opened()
        flow.startRequestEdit()
        assertEquals(RequestEdit("Add B", "Adds B.", "Add B", "Adds B."), flow.sheet.value!!.drafts.request)
        flow.editRequestTitle("Add B, faster")
        flow.close()
        opened()
        flow.startRequestEdit()
        assertEquals("reopening keeps the edit typed, not the text read", "Add B, faster", flow.sheet.value!!.drafts.request?.title)

        current = REVIEW.copy(title = "Add B (web)")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.FAILED, "The pull request's title or description changed since you opened it.")
        flow.saveRequest()
        val rebased = eventually { flow.sheet.value?.drafts?.request?.takeIf { it.rebased } }
        assertEquals("Add B", sent.single().baseTitle)
        assertEquals("Add B, faster", rebased.title)
        assertEquals("the base is now what the service says", "Add B (web)", rebased.baseTitle)
        assertEquals("Adds B.", rebased.baseBody)

        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Saved the pull request.")
        flow.saveRequest()
        eventually { flow.sheet.value?.takeIf { it.working == null && it.drafts.request == null } }
        assertEquals("Add B (web)", sent[1].baseTitle)
        assertNotEquals("a rebased edit is a new operation", sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a refused edit of unchanged text keeps its base`() {
        opened()
        flow.startRequestEdit()
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.FAILED, "Nothing changed.")
        val before = reads.get()
        flow.saveRequest()
        eventually { flow.sheet.value?.error }
        eventually { flow.sheet.value?.takeIf { reads.get() > before && !it.reloading }?.drafts?.request?.takeIf { !it.recheck } }
        assertEquals(RequestEdit("Add B", "Adds B.", "Add B", "Adds B."), flow.sheet.value!!.drafts.request)
    }

    @Test fun `comment edits are kept per comment, name their base, and Cancel drops only one`() {
        opened()
        flow.startCommentEdit("t1", "c1")
        assertEquals(CommentEdit("t1", "c1", "nit", "nit"), flow.sheet.value!!.drafts.comments["c1"])
        flow.editCommentText("c1", "nit: rename")
        flow.startCommentEdit("t1", "c2")
        assertEquals("a second pencil keeps the first edit", "nit: rename", flow.sheet.value!!.drafts.comments["c1"]?.body)
        flow.cancelCommentEdit("c2")
        assertEquals(setOf("c1"), flow.sheet.value!!.drafts.comments.keys)

        current = REVIEW.copy(threads = listOf(REVIEW.threads.single().let { t -> t.copy(comments = listOf(t.comments[0].copy(body = "nit (edited)"), t.comments[1])) }))
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.FAILED, "That comment changed since you opened it.")
        flow.saveComment("c1")
        val rebased = eventually { flow.sheet.value?.drafts?.comments?.get("c1")?.takeIf { it.rebased } }
        assertEquals("nit", sent.single().baseBody)
        assertEquals("c1", sent.single().commentId)
        assertEquals("nit: rename", rebased.body)
        assertEquals("nit (edited)", rebased.base)

        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Saved the comment.")
        flow.saveComment("c1")
        eventually { flow.sheet.value?.takeIf { it.working == null && it.drafts.comments.isEmpty() } }
        assertEquals(MobileChangeRequestReviewRequest.EDIT_COMMENT, sent[1].action)
        assertEquals("nit (edited)", sent[1].baseBody)
    }

    /**
     * **A tick shows at once and reaches the machine's store; one the machine refused is put back.**
     * The box is the reader's own record, so it never waits behind a post or blocks one.
     */
    @Test fun `a viewed tick shows at once, is sent in tap order, and a refused one re-reads the machine`() {
        current = REVIEW.copy(files = FILES)
        opened()
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "")
        flow.setViewed(listOf("src/App.kt"), true)
        assertEquals("the sheet ticks before the machine answers", listOf(true, false), flow.sheet.value!!.review!!.files.map { it.viewed })
        eventually { sent.firstOrNull() }
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.FAILED, "This worktree changed. Open Manage worktrees again and retry.")
        val before = reads.get()
        flow.setViewed(listOf("src/App.kt", "README.md"), true)

        val error = eventually { flow.sheet.value?.error }
        assertEquals("This worktree changed. Open Manage worktrees again and retry.", error)
        assertEquals(listOf(MobileChangeRequestReviewRequest.VIEW_FILES), sent.map { it.action }.distinct())
        assertEquals(listOf(listOf("src/App.kt"), listOf("src/App.kt", "README.md")), sent.map { it.paths })
        assertEquals("7", sent.last().requestId)
        eventually { reads.get().takeIf { it > before } }
        eventually { flow.sheet.value?.takeIf { !it.reloading } }
        assertEquals("the boxes say what the machine read back", listOf(false, false), flow.sheet.value!!.review!!.files.map { it.viewed })
        assertNull("a tick is not a post the sheet waits on", flow.sheet.value!!.working)
    }

    /**
     * **A stack link reads the neighbour's request inside the same worktree, and its verbs and drafts are
     * that request's** — a reply typed on the parent is neither sent to nor shown on the child.
     */
    @Test fun `a stack link steps to the neighbour, whose verbs and drafts are its own`() {
        current = REVIEW.copy(stackParent = MobileChangeRequestStackEntry("6", "base-a", "#6 Add A"))
        opened()
        flow.editReply("t1", "for seven")
        flow.step("base-a")
        val parent = eventually { flow.sheet.value?.takeIf { !it.reloading }?.review?.takeIf { it.requestId == "6" } }
        assertTrue(queries.last(), "request=base-a" in queries.last())
        assertEquals("base-a", flow.sheet.value!!.shownBranch)
        assertNull("the child's reply is not on the parent", flow.sheet.value!!.drafts.replies["t1"])
        assertEquals("#7 Add B", parent.stackChildren.single().label)

        flow.editReply("t1", "for six")
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Replied.")
        flow.reply("t1")
        eventually { snacks.firstOrNull() }
        assertEquals("6", sent.single().requestId)
        assertEquals("base-a", sent.single().requestBranch)
        assertEquals("the worktree is still checked against its own branch", "feature", sent.single().branch)

        flow.step("feature")
        eventually { flow.sheet.value?.takeIf { !it.reloading }?.review?.takeIf { it.requestId == "7" } }
        assertNull(flow.sheet.value!!.requestBranch)
        assertTrue(queries.last(), "request=" !in queries.last())
        assertEquals("for seven", flow.sheet.value!!.drafts.replies["t1"])
    }

    @Test fun `label suggestions complete the name being typed from the repository's labels`() {
        val repo = listOf("bug", "build", "documentation", "tests")
        assertEquals(listOf("bug", "build"), labelSuggestions("tests, b", repo))
        assertEquals("a label already typed is not offered again", listOf("bug", "build", "documentation"), labelSuggestions("tests, ", repo))
        assertEquals("tests, build", completeLabel("tests, b", "build"))
        assertEquals("bug", completeLabel("b", "bug"))
    }

    private fun opened(): MobileChangeRequestReview {
        flow.open(PROJECT, PATH, "feature")
        return eventually { flow.sheet.value?.takeIf { !it.reloading }?.review }
    }

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private var answer = "{}"
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileChangeRequestReviewRequest.ROUTE) return 404
                if (body.size() == 0) {
                    reads.incrementAndGet()
                    queries += url.query.orEmpty()
                    answer = (if ("request=base-a" in url.query.orEmpty()) PARENT else current).toJson().toString()
                    return 200
                }
                sent += MobileChangeRequestReviewRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                val queued = synchronized(answers) { if (answers.isEmpty() && holding) HOLD else answers.removeFirst() }
                return when (val next = queued) {
                    null -> throw IOException("connection reset")
                    is MobileRefusal -> { answer = next.toJson().toString(); next.status }
                    else -> { answer = (next as MobileWorktreeActionResult).toJson().toString(); 200 }
                }
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        val HOLD = MobileWorktreeActionResult(MobileWorktreeActionResult.WORKING)
        const val PROJECT = "/work/repo"
        const val PATH = "$PROJECT/.claude/worktrees/feature"
        val REVIEW = MobileChangeRequestReview(
            PROJECT, PATH, "feature", requestId = "7", url = "https://github.com/acme/app/pull/7", title = "Add B", base = "main",
            forge = "GitHub",
            threads = listOf(
                MobileChangeRequestThread("t1", "src/App.kt:42 · 1 comment", resolvable = true, comments = listOf(MobileChangeRequestComment("ana", "nit", "c1"), MobileChangeRequestComment("bo", "ok", "c2"))),
            ),
            body = "Adds B.",
        )
        val FILES = listOf(MobileChangeRequestFile("src/App.kt", 12, 3), MobileChangeRequestFile("README.md", 0, 1))
        val PARENT = REVIEW.copy(
            requestId = "6", title = "Add A", requestBranch = "base-a",
            stackChildren = listOf(MobileChangeRequestStackEntry("7", "feature", "#7 Add B")),
        )
    }
}
