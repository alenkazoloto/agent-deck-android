package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileWorktreeActionRequest
import com.github.claudeagents.core.mobile.MobileWorktreeActionResult
import com.github.claudeagents.core.mobile.MobileWorktreeFleet
import com.github.claudeagents.core.mobile.MobileWorktreeRow
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.WorktreeConfirm
import dev.agentdeck.companion.data.WorktreeManageFlow
import dev.agentdeck.companion.ui.worktreeConfirmation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Manage worktrees (M4, P22): Merge and Remove confirm first and carry what the confirmation named,
 * Prune runs at once, a write that outlasts one request is followed under its operation id, a lost
 * answer is asked again under the same id, and every outcome re-reads the machine's rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorktreeManageFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<MobileWorktreeActionRequest>()
    private val snacks = CopyOnWriteArrayList<String>()
    private val reads = AtomicInteger()
    /** A result, a [MobileRefusal] the machine answers with, or null — thrown, as a dropped connection is. */
    private val answers = ArrayDeque<Any?>()
    private val flow = WorktreeManageFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, { snacks += it }, pollMs = 1)

    @After fun tearDown() = scope.cancel()

    @Test fun `remove confirms first and carries the discard its confirmation named`() {
        val sheet = opened()
        val dirty = sheet.rows.first { it.name == "dirty" }
        flow.ask(dirty, MobileWorktreeActionRequest.REMOVE)
        assertEquals("nothing runs before the yes", 0, sent.size)
        assertEquals(MobileWorktreeActionRequest.REMOVE, flow.sheet.value!!.confirm!!.action)

        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Removed dirty.")
        val before = reads.get()
        flow.confirm()
        eventually { snacks.firstOrNull() }

        val request = sent.single()
        assertEquals("the confirmation's count of uncommitted files", 1, request.discardFiles)
        assertEquals("dirty", request.branch)
        assertNull(request.baseBranch)
        assertEquals("Removed dirty.", snacks.single())
        eventually { flow.sheet.value?.takeIf { it.working == null && it.confirm == null } }
        eventually { reads.get().takeIf { it > before } }
    }

    @Test fun `merge names the branch and base, and a long merge is followed under one operation`() {
        val sheet = opened()
        val clean = sheet.rows.first { it.name == "clean" }
        flow.ask(clean, MobileWorktreeActionRequest.MERGE)
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.WORKING)
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Merged clean into main.")
        flow.confirm()
        eventually { snacks.firstOrNull() }

        assertEquals(2, sent.size)
        assertEquals("a merge is followed, never repeated", 1, sent.map { it.operationId }.distinct().size)
        assertEquals("clean", sent.first().branch)
        assertEquals("main", sent.first().baseBranch)
        assertNull(sent.first().discardFiles)
    }

    @Test fun `prune runs without a confirmation, as the desk's entry does`() {
        val gone = opened().rows.first { it.missing }
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Pruned worktrees.")
        flow.ask(gone, MobileWorktreeActionRequest.PRUNE)
        eventually { snacks.firstOrNull() }
        assertEquals(MobileWorktreeActionRequest.PRUNE, sent.single().action)
    }

    @Test fun `a refusal is shown in the machine's words and nothing is snacked`() {
        val clean = opened().rows.first { it.name == "clean" }
        flow.ask(clean, MobileWorktreeActionRequest.MERGE)
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.FAILED, "This worktree changed. Open Manage worktrees again and retry.")
        flow.confirm()
        val failed = eventually { flow.sheet.value?.error }
        assertEquals("This worktree changed. Open Manage worktrees again and retry.", failed)
        assertTrue(snacks.isEmpty())
    }

    @Test fun `an action whose answer never arrived is asked again under the same operation`() {
        val clean = opened().rows.first { it.name == "clean" }
        flow.ask(clean, MobileWorktreeActionRequest.MERGE)
        answers += null
        flow.confirm()
        eventually { flow.sheet.value?.error }
        eventually { flow.sheet.value?.takeIf { it.working == null } }

        flow.ask(clean, MobileWorktreeActionRequest.MERGE)
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Merged clean into main.")
        flow.confirm()
        eventually { snacks.firstOrNull() }
        assertEquals("a lost answer must not become a second merge", sent[0].operationId, sent[1].operationId)

        flow.ask(clean, MobileWorktreeActionRequest.REMOVE)
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Removed clean.")
        flow.confirm()
        eventually { snacks.takeIf { it.size == 2 } }
        assertNotEquals("another action is another operation", sent[1].operationId, sent[2].operationId)
    }

    @Test fun `a change request confirms, names the branch, and leaves its page as a link`() {
        val clean = opened().rows.first { it.name == "clean" }
        flow.ask(clean, MobileWorktreeActionRequest.REQUEST_DRAFT)
        assertEquals("pushing publishes the branch, so it waits for the yes", 0, sent.size)
        answers += MobileWorktreeActionResult(MobileWorktreeActionResult.DONE, "Opened a draft pull request for clean.", "https://github.com/o/r/pull/7")
        flow.confirm()
        val link = eventually { flow.sheet.value?.link }

        val request = sent.single()
        assertEquals(MobileWorktreeActionRequest.REQUEST_DRAFT, request.action)
        assertEquals("clean", request.branch)
        assertNull(request.baseBranch)
        assertEquals("https://github.com/o/r/pull/7", link.url)
        assertEquals("Opened a draft pull request for clean.", link.message)

        flow.ask(clean, MobileWorktreeActionRequest.MERGE)
        assertNull("the next action clears the last one's link", flow.sheet.value!!.link)
    }

    @Test fun `the confirmations say what the desk's dialogs say`() {
        val rows = FLEET.rows
        assertEquals(
            "Merge clean into main in the machine's checkout?\n\n2 commit(s) will be merged. Conflicts stop the merge and are left for you to resolve on the machine.",
            worktreeConfirmation(WorktreeConfirm(rows[1], MobileWorktreeActionRequest.MERGE, "main")),
        )
        assertEquals(
            "dirty has 1 uncommitted file(s) that removing it would discard.\n\nRemove dirty and discard them?",
            worktreeConfirmation(WorktreeConfirm(rows[0], MobileWorktreeActionRequest.REMOVE, "main")),
        )
        assertEquals(
            "Remove the worktree clean?\n\nIts branch clean is kept.",
            worktreeConfirmation(WorktreeConfirm(rows[1], MobileWorktreeActionRequest.REMOVE, "main")),
        )
        assertEquals(
            "Push clean and open a merge request against the worktree branch it was cut from, not main? That branch is pushed too.",
            worktreeConfirmation(WorktreeConfirm(rows[1], MobileWorktreeActionRequest.REQUEST_STACKED, "main"), "merge request"),
        )
    }

    private fun opened(): MobileWorktreeFleet {
        flow.open(PROJECT)
        return eventually { flow.sheet.value?.fleet }
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
                if (url.path != MobileWorktreeActionRequest.ROUTE) return 404
                if (body.size() == 0) {
                    reads.incrementAndGet()
                    answer = FLEET.toJson().toString()
                    return 200
                }
                sent += MobileWorktreeActionRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                return when (val next = answers.removeFirst()) {
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
        const val PROJECT = "/work/repo"
        val FLEET = MobileWorktreeFleet(
            PROJECT, "main", requestNoun = "pull request",
            rows = listOf(
                MobileWorktreeRow("dirty", "$PROJECT/.claude/worktrees/dirty", "dirty", ahead = 1, dirtyFiles = 1, discardWarning = "dirty has 1 uncommitted file(s) that removing it would discard."),
                MobileWorktreeRow("clean", "$PROJECT/.claude/worktrees/clean", "clean", ahead = 2),
                MobileWorktreeRow("gone", "$PROJECT/.claude/worktrees/gone", "gone", missing = true),
            ),
        )
    }
}
