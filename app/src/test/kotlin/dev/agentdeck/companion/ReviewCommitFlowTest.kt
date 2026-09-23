package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileReviewCommitFile
import com.github.claudeagents.core.mobile.MobileReviewCommitPreview
import com.github.claudeagents.core.mobile.MobileReviewCommitRequest
import com.github.claudeagents.core.mobile.MobileReviewCommitResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.ReviewCommitFlow
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
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

/**
 * Commit from the Changes tab (M4, P21): the reader confirms the machine's preview, the request
 * carries that preview's token and the files left ticked, a failed commit keeps the typed message,
 * and a commit whose answer never arrived is retried under the same operation id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewCommitFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val snacks = CopyOnWriteArrayList<String>()
    private val committedKeys = CopyOnWriteArrayList<String>()
    private val sent = CopyOnWriteArrayList<MobileReviewCommitRequest>()
    private val answers = ArrayDeque<Pair<Int, String>>()
    @Volatile private var preview = PREVIEW

    private val flow = ReviewCommitFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, { snacks += it }) { committedKeys += it }

    @After fun tearDown() = scope.cancel()

    @Test fun `the confirmed sheet commits the ticked files under the preview's token`() {
        flow.open(KEY)
        val sheet = eventually { flow.sheet.value?.takeIf { it.preview != null } }
        assertEquals("the desk's seed", PREVIEW.message, sheet.message)
        assertEquals("an untracked file starts unticked, as on the desk", setOf("src/A.kt"), sheet.chosen)

        flow.toggle("src/New.kt")
        answers += 200 to MobileReviewCommitResult(KEY, true, "Committed “Fix it” on main.", "abc1234").toJson().toString()
        flow.confirm()
        eventually { committedKeys.firstOrNull() }

        val request = sent.single()
        assertEquals("t1", request.previewToken)
        assertEquals(setOf("src/A.kt", "src/New.kt"), request.paths.toSet())
        assertEquals(PREVIEW.message, request.message)
        assertNull(flow.sheet.value)
        assertEquals("Committed “Fix it” on main. (abc1234)", snacks.single())
    }

    @Test fun `a refused commit keeps the sheet, its error and the typed message across a reopen`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        flow.editMessage("My own words")
        answers += 200 to MobileReviewCommitResult(KEY, false, "lint failed").toJson().toString()
        flow.confirm()

        val failed = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals("lint failed", failed.error)
        flow.dismiss()
        flow.open(KEY)
        val again = eventually { flow.sheet.value?.takeIf { it.preview != null } }
        assertEquals("the draft is kept until a commit lands", "My own words", again.message)
        assertTrue(committedKeys.isEmpty())
    }

    @Test fun `an unconfirmed commit is retried as the same operation, a definite answer retires it`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        answers += MobileRefusal.COMMIT_UNCONFIRMED.status to MobileRefusal.COMMIT_UNCONFIRMED.toJson().toString()
        flow.confirm()
        eventually { flow.sheet.value?.error }
        answers += 200 to MobileReviewCommitResult(KEY, false, "hook failed").toJson().toString()
        flow.confirm()
        eventually { flow.sheet.value?.error?.takeIf { it == "hook failed" } }
        answers += 200 to MobileReviewCommitResult(KEY, false, "hook failed").toJson().toString()
        flow.confirm()
        eventually { sent.takeIf { it.size == 3 } }

        assertEquals("a retry after no answer must not be a second commit", sent[0].operationId, sent[1].operationId)
        assertNotEquals(sent[1].operationId, sent[2].operationId)
    }

    @Test fun `a request edited after an unconfirmed answer is a new operation`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        answers += MobileRefusal.COMMIT_UNCONFIRMED.status to MobileRefusal.COMMIT_UNCONFIRMED.toJson().toString()
        flow.confirm()
        eventually { flow.sheet.value?.error }
        flow.editMessage("A different message")
        answers += 200 to MobileReviewCommitResult(KEY, false, "stale").toJson().toString()
        flow.confirm()
        eventually { sent.takeIf { it.size == 2 } }

        assertNotEquals("the host would answer the old commit for a request it never saw", sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a stale preview is read again with the machine's reason`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        preview = PREVIEW.copy(previewToken = "t2")
        answers += MobileRefusal.COMMIT_PREVIEW_STALE.status to MobileRefusal.COMMIT_PREVIEW_STALE.toJson().toString()
        flow.confirm()

        val fresh = eventually { flow.sheet.value?.takeIf { it.preview?.previewToken == "t2" } }
        assertEquals(MobileRefusal.COMMIT_PREVIEW_STALE.message, fresh.error)
        assertTrue(committedKeys.isEmpty())
    }

    @Test fun `a machine with nothing to commit says why and opens no sheet`() {
        preview = MobileReviewCommitPreview(KEY, refused = "Nothing to commit.")
        flow.open(KEY)
        eventually { snacks.firstOrNull() }

        assertEquals("Nothing to commit.", snacks.single())
        assertNull(flow.sheet.value)
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
                if (!url.path.endsWith("/commit")) return 404
                if (body.size() == 0) {
                    answer = preview.toJson().toString()
                    return 200
                }
                sent += MobileReviewCommitRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                val (status, text) = answers.removeFirst()
                answer = text
                return status
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude:/repo:session"
        val PREVIEW = MobileReviewCommitPreview(
            key = KEY,
            branch = "main",
            message = "Fix it\n\nClaude changed 2 files",
            files = listOf(
                MobileReviewCommitFile("src/A.kt", MobileReviewCommitFile.MODIFIED, true),
                MobileReviewCommitFile("src/New.kt", MobileReviewCommitFile.UNTRACKED, false),
            ),
            previewToken = "t1",
        )
    }
}
