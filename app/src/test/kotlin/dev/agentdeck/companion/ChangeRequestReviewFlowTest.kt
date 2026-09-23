package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileChangeRequestComment
import com.github.claudeagents.core.mobile.MobileChangeRequestReview
import com.github.claudeagents.core.mobile.MobileChangeRequestReviewRequest
import com.github.claudeagents.core.mobile.MobileChangeRequestThread
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileWorktreeActionResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.ChangeRequestReviewFlow
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
    /** A result, a [MobileRefusal] the machine answers with, or null — thrown, as a dropped connection is. */
    private val answers = ArrayDeque<Any?>()
    private var machine = "studio"
    /** While set, a post with no queued answer is still working on the machine. */
    @Volatile private var holding = false
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
                    answer = REVIEW.toJson().toString()
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
                MobileChangeRequestThread("t1", "src/App.kt:42 · 1 comment", resolvable = true, comments = listOf(MobileChangeRequestComment("ana", "nit"))),
            ),
        )
    }
}
