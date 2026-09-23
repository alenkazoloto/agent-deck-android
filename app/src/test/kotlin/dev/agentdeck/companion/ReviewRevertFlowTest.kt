package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileReviewRevertFile
import com.github.claudeagents.core.mobile.MobileReviewRevertPreview
import com.github.claudeagents.core.mobile.MobileReviewRevertRequest
import com.github.claudeagents.core.mobile.MobileReviewRevertResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.ReviewRevertFlow
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
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

/**
 * Revert from the Changes tab (M4, P20): the reader confirms the machine's preview, the request
 * carries that preview's token and only the files left ticked, a file the machine cannot revert
 * can never be ticked, and a revert whose answer never arrived is retried under the same id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewRevertFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val snacks = CopyOnWriteArrayList<String>()
    private val revertedKeys = CopyOnWriteArrayList<String>()
    private val sent = CopyOnWriteArrayList<MobileReviewRevertRequest>()
    private val answers = ArrayDeque<Pair<Int, String>>()
    @Volatile private var preview = PREVIEW

    private val flow = ReviewRevertFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, { snacks += it }) { revertedKeys += it }

    @After fun tearDown() = scope.cancel()

    @Test fun `the confirmed sheet reverts only the ticked files under the preview's token`() {
        flow.open(KEY)
        val sheet = eventually { flow.sheet.value?.takeIf { it.preview != null } }
        assertEquals("every file the machine can revert starts ticked", setOf("src/A.kt", "src/New.kt"), sheet.chosen)

        flow.toggle("src/New.kt")
        answers += 200 to MobileReviewRevertResult(KEY, true, "Reverted 1 file to session start.").toJson().toString()
        flow.confirm()
        eventually { revertedKeys.firstOrNull() }

        val request = sent.single()
        assertEquals("t1", request.previewToken)
        assertEquals("an unticked file was sent for revert", listOf("src/A.kt"), request.paths)
        assertNull(flow.sheet.value)
        assertEquals("Reverted 1 file to session start.", snacks.single())
    }

    @Test fun `a file with no recorded start cannot be ticked`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }

        flow.toggle("src/Unrecorded.kt")

        assertFalse("src/Unrecorded.kt" in flow.sheet.value!!.chosen)
    }

    @Test fun `a refused revert keeps the sheet open under the machine's reason`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        answers += 200 to MobileReviewRevertResult(KEY, false, "The transcript is still being written.").toJson().toString()
        flow.confirm()

        val failed = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals("The transcript is still being written.", failed.error)
        assertFalse(failed.reverting)
        assertTrue(revertedKeys.isEmpty())
    }

    @Test fun `an unconfirmed revert is retried as the same operation, a definite answer retires it`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        answers += MobileRefusal.REVERT_UNCONFIRMED.status to MobileRefusal.REVERT_UNCONFIRMED.toJson().toString()
        flow.confirm()
        eventually { flow.sheet.value?.error }
        answers += 200 to MobileReviewRevertResult(KEY, false, "nothing").toJson().toString()
        flow.confirm()
        eventually { flow.sheet.value?.error?.takeIf { it == "nothing" } }
        answers += 200 to MobileReviewRevertResult(KEY, false, "nothing").toJson().toString()
        flow.confirm()
        eventually { sent.takeIf { it.size == 3 } }

        assertEquals("a retry after no answer must not be a second revert", sent[0].operationId, sent[1].operationId)
        assertNotEquals(sent[1].operationId, sent[2].operationId)
    }

    @Test fun `a different selection after an unconfirmed answer is a new operation`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        answers += MobileRefusal.REVERT_UNCONFIRMED.status to MobileRefusal.REVERT_UNCONFIRMED.toJson().toString()
        flow.confirm()
        eventually { flow.sheet.value?.error }
        flow.toggle("src/New.kt")
        answers += 200 to MobileReviewRevertResult(KEY, false, "stale").toJson().toString()
        flow.confirm()
        eventually { sent.takeIf { it.size == 2 } }

        assertNotEquals("the host would answer the old revert for a request it never saw", sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a stale preview is read again with the machine's reason`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        preview = PREVIEW.copy(previewToken = "t2")
        answers += MobileRefusal.REVERT_PREVIEW_STALE.status to MobileRefusal.REVERT_PREVIEW_STALE.toJson().toString()
        flow.confirm()

        val fresh = eventually { flow.sheet.value?.takeIf { it.preview?.previewToken == "t2" } }
        assertEquals(MobileRefusal.REVERT_PREVIEW_STALE.message, fresh.error)
        assertTrue(revertedKeys.isEmpty())
    }

    @Test fun `a stale re-read keeps the file the reader unticked unticked`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.preview }
        flow.toggle("src/New.kt")
        preview = PREVIEW.copy(previewToken = "t2")
        answers += MobileRefusal.REVERT_PREVIEW_STALE.status to MobileRefusal.REVERT_PREVIEW_STALE.toJson().toString()
        flow.confirm()

        val fresh = eventually { flow.sheet.value?.takeIf { it.preview?.previewToken == "t2" } }
        assertEquals("the file the reader left out was ticked again", setOf("src/A.kt"), fresh.chosen)
    }

    @Test fun `a machine that will not revert says why and opens no sheet`() {
        preview = MobileReviewRevertPreview(KEY, refused = "Can't revert while this session is active.")
        flow.open(KEY)
        eventually { snacks.firstOrNull() }

        assertEquals("Can't revert while this session is active.", snacks.single())
        assertNull(flow.sheet.value)
    }

    @Test fun `an action this client does not know is never offered as a write`() {
        val decoded = MobileReviewRevertFile.fromJson(
            MobileProtocol.parseObject("""{"path":"src/A.kt","action":"shred"}""")!!,
        )
        assertEquals(MobileReviewRevertFile.SKIP, decoded.action)
        assertFalse(decoded.revertable)
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
                if (!url.path.endsWith("/revert")) return 404
                if (body.size() == 0) {
                    answer = preview.toJson().toString()
                    return 200
                }
                sent += MobileReviewRevertRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
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
        val PREVIEW = MobileReviewRevertPreview(
            key = KEY,
            files = listOf(
                MobileReviewRevertFile("src/A.kt", MobileReviewRevertFile.RESTORE),
                MobileReviewRevertFile("src/New.kt", MobileReviewRevertFile.DELETE),
                MobileReviewRevertFile("src/Unrecorded.kt", MobileReviewRevertFile.SKIP),
            ),
            notes = listOf("Files change; git is not touched — commits stay on the branch."),
            previewToken = "t1",
        )
    }
}
