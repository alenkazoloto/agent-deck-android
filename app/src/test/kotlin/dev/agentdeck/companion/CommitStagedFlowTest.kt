package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileCommitStagedPreview
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileCommitStagedRequest
import com.github.claudeagents.core.mobile.MobileCommitStagedResult
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewCommitFile
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.CommitStagedFlow
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.RowAction
import dev.agentdeck.companion.data.RowActions
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
import javax.net.ssl.HttpsURLConnection

/**
 * Commit staged changes (M4, P22): the sheet opens on the desk's saved draft, a written message
 * fills only what is empty, a commit that outlasts one request is followed under its operation id,
 * a lost answer is asked again under the same id, and a moved index re-reads before committing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommitStagedFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<MobileCommitStagedRequest>()
    private val snacks = CopyOnWriteArrayList<String>()
    private val committed = CopyOnWriteArrayList<String>()
    /** A result, or null — thrown, as a dropped connection is. */
    private val answers = ArrayDeque<MobileCommitStagedResult?>()
    @Volatile private var preview = PREVIEW
    @Volatile private var previews = 0
    /** When set, a POST waits on it: the machine's answer arrives when the test says so. */
    @Volatile private var gate: java.util.concurrent.CountDownLatch? = null

    private val flow = CommitStagedFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, { snacks += it }, { committed += it }, pollMs = 1)

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet opens on the desk's draft and a commit is followed until it lands`() {
        flow.open(KEY)
        val sheet = eventually { flow.sheet.value?.takeIf { it.preview != null } }
        assertEquals("Fix the loader", sheet.subject)
        assertEquals("Budget the reads", sheet.body)

        answers += MobileCommitStagedResult(MobileCommitStagedResult.WORKING)
        answers += MobileCommitStagedResult(MobileCommitStagedResult.COMMITTED, "Committed “Fix the loader”.", commit = "abc1234")
        flow.confirm()
        eventually { committed.firstOrNull() }

        assertEquals(2, sent.size)
        assertEquals("a commit is followed, never repeated", 1, sent.map { it.operationId }.distinct().size)
        assertEquals(MobileCommitStagedRequest.COMMIT, sent.first().action)
        assertEquals(TOKEN, sent.first().previewToken)
        assertNull(sent.first().renameTo)
        assertEquals("Committed “Fix the loader”. (abc1234)", snacks.single())
        assertNull(flow.sheet.value)

        flow.open(KEY)
        val again = eventually { flow.sheet.value?.takeIf { it.preview != null } }
        assertEquals("a landed commit clears the phone's draft; the machine's seed is back", PREVIEW.message.lines().first(), again.subject)
    }

    @Test fun `a written message fills only the empty fields and the suggested branch`() {
        preview = PREVIEW.copy(message = "", renamable = true)
        flow.open(KEY)
        eventually { flow.sheet.value?.takeIf { it.preview != null } }
        flow.editSubject("Mine")
        answers += MobileCommitStagedResult(MobileCommitStagedResult.WRITTEN, subject = "Theirs", body = "Written body", branch = "fix-loader")
        flow.write()
        val written = eventually { flow.sheet.value?.takeIf { it.body.isNotEmpty() } }

        assertEquals("a typed subject outranks a written one", "Mine", written.subject)
        assertEquals("Written body", written.body)
        assertEquals("fix-loader", written.renameTo)
        assertFalse("the rename stays the reader's choice", written.rename)
        assertEquals(MobileCommitStagedRequest.WRITE, sent.single().action)

        flow.setRename(true)
        answers += MobileCommitStagedResult(MobileCommitStagedResult.COMMITTED, "Committed “Mine” on fix-loader.")
        flow.confirm()
        eventually { committed.firstOrNull() }
        assertEquals("fix-loader", sent.last().renameTo)
    }

    @Test fun `a commit whose answer never arrived is asked again under the same operation`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.takeIf { it.preview != null } }
        answers += null
        flow.confirm()
        val failed = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertFalse(failed.committing)
        assertEquals("Fix the loader", failed.subject)

        answers += MobileCommitStagedResult(MobileCommitStagedResult.COMMITTED, "Committed.")
        flow.confirm()
        eventually { committed.firstOrNull() }
        assertEquals("a lost answer must not become a second commit", sent[0].operationId, sent[1].operationId)
    }

    @Test fun `an edited retry is a new operation`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.takeIf { it.preview != null } }
        answers += null
        flow.confirm()
        eventually { flow.sheet.value?.error }
        flow.editSubject("Different")
        answers += MobileCommitStagedResult(MobileCommitStagedResult.FAILED, "no")
        flow.confirm()
        eventually { sent.takeIf { it.size == 2 } }
        assertNotEquals(sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a moved index re-reads the preview and keeps the typed message`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.takeIf { it.preview != null } }
        flow.editSubject("Typed")
        answers += MobileCommitStagedResult(MobileCommitStagedResult.STALE, "What is staged changed since you opened this.")
        flow.confirm()
        val reread = eventually { flow.sheet.value?.takeIf { previews == 2 && it.preview != null } }

        assertEquals("What is staged changed since you opened this.", reread.error)
        assertEquals("Typed", reread.subject)
        assertTrue(committed.isEmpty())
    }

    @Test fun `a message written for one chat never fills another chat's sheet`() {
        preview = PREVIEW.copy(message = "")
        flow.open(KEY)
        eventually { flow.sheet.value?.takeIf { it.preview != null } }
        // Held: the write's answer arrives only after the reader has moved to another chat.
        gate = java.util.concurrent.CountDownLatch(1)
        answers += MobileCommitStagedResult(MobileCommitStagedResult.WRITTEN, subject = "For the first chat", body = "b")
        flow.write()
        flow.dismiss()
        flow.open(OTHER)
        eventually { flow.sheet.value?.takeIf { it.key == OTHER && it.preview != null } }
        gate!!.countDown()
        eventually { answers.takeIf { it.isEmpty() } }
        Thread.sleep(50)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val other = flow.sheet.value!!

        assertEquals("", other.subject)
        assertEquals("", other.body)
    }

    @Test fun `nothing staged is said once and leaves no sheet`() {
        preview = MobileCommitStagedPreview(KEY, refused = "Nothing is staged on main. Stage the changes you want committed first.")
        flow.open(KEY)
        eventually { snacks.firstOrNull() }
        assertNull(flow.sheet.value)
    }

    @Test fun `the row offers the commit only when the machine serves it, running or not`() {
        val row = MobileFleetRow(
            key = KEY, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
            projectName = "repo", gitBranch = null, title = "t", attention = null, waitingReason = null,
            lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        )
        assertFalse(RowAction.COMMIT_STAGED in RowActions.of(FleetGroup.RUNNING, row = row))
        assertTrue(RowAction.COMMIT_STAGED in RowActions.of(FleetGroup.RUNNING, row = row, canCommitStaged = true))
        assertTrue(RowAction.COMMIT_STAGED in RowActions.of(FleetGroup.DONE_UNREVIEWED, row = row, canCommitStaged = true))
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
                if (url.path != MobileCommitStagedRequest.ROUTE) return 404
                if (body.size() == 0) {
                    previews++
                    answer = preview.toJson().toString()
                    return 200
                }
                sent += MobileCommitStagedRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                gate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                val next = answers.removeFirst() ?: throw IOException("connection reset")
                answer = next.toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude|default|s1"
        const val OTHER = "claude|default|s2"
        const val TOKEN = "t0k3n"
        val PREVIEW = MobileCommitStagedPreview(
            key = KEY,
            branch = "main",
            files = listOf(MobileReviewCommitFile("src/Loader.kt", MobileReviewCommitFile.MODIFIED, included = true)),
            message = "Fix the loader\n\nBudget the reads",
            previewToken = TOKEN,
        )
    }
}
