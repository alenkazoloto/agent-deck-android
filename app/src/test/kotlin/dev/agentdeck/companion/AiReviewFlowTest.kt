package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileAiReviewFinding
import com.github.claudeagents.core.mobile.MobileAiReviewReport
import com.github.claudeagents.core.mobile.MobileAiReviewRequest
import com.github.claudeagents.core.mobile.MobileAiReviewState
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.AiReviewFlow
import dev.agentdeck.companion.data.BridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Codex's `/review` from the phone (M4, P20): the sheet opens on the machine's form, a start
 * answers "running" and the sheet reads on by itself until the findings land; a start whose
 * answer was lost is retried under its first id, so the machine runs one review, not two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiReviewFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val posts = CopyOnWriteArrayList<MobileAiReviewRequest>()
    private val reads = AtomicInteger()
    /** What the machine holds; a POST start makes it running, [finish] lands the report. */
    @Volatile private var machine = MobileAiReviewState(KEY, kind = MobileAiReviewRequest.BASE, branch = "main", spendNote = "Runs Codex.")
    @Volatile private var dropNextPost = false
    @Volatile private var refuseWith: String? = null

    private val flow = AiReviewFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet opens on the machine's form and a start sends it trimmed, with an operation id`() {
        flow.open(KEY)
        val sheet = eventually { flow.sheet.value?.takeIf { it.state != null } }
        assertEquals(MobileAiReviewRequest.BASE, sheet.kind)
        assertEquals("main", sheet.branch)

        flow.edit(branch = " release ")
        flow.start()

        eventually { flow.sheet.value?.state?.takeIf { it.running } }
        val start = posts.single()
        assertEquals(MobileAiReviewRequest.BASE, start.kind)
        assertEquals("release", start.branch)
        assertNotNull(start.operationId)
        assertFalse(start.stop)
    }

    @Test fun `a running review is read again until its findings land, with no tap`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.state }
        flow.start()
        eventually { flow.sheet.value?.state?.takeIf { it.running } }

        finish()

        val done = eventually { flow.sheet.value?.state?.takeIf { !it.running } }
        assertEquals("1 finding.", done.report?.summary)
        assertEquals("src/Calc.kt:2-3", done.report?.findings?.single()?.locationLabel)
        assertEquals("one run", 1, posts.size)
    }

    @Test fun `a start whose answer was lost is retried as the same review`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.state }
        dropNextPost = true
        flow.start()
        eventually { flow.sheet.value?.takeIf { it.error != null && !it.sending } }

        flow.start()

        eventually { flow.sheet.value?.state?.takeIf { it.running } }
        assertEquals(2, posts.size)
        assertEquals("the machine answers the retry with the run it started", posts[0].operationId, posts[1].operationId)
    }

    @Test fun `once a read shows the lost start's review ended, Review again is a new review`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.state }
        dropNextPost = true
        flow.start()
        eventually { flow.sheet.value?.takeIf { it.error != null && !it.sending } }
        finish()
        flow.dismiss()

        flow.open(KEY)
        eventually { flow.sheet.value?.state?.report }
        flow.start()

        eventually { flow.sheet.value?.state?.takeIf { it.running } }
        assertEquals(2, posts.size)
        assertTrue("not a retry the machine answers with the old run", posts[0].operationId != posts[1].operationId)
    }

    @Test fun `a refused start keeps the sheet and the typed target, under the machine's words`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.state }
        refuseWith = "That is not a branch name."
        flow.edit(branch = "--oops")
        flow.start()

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals("That is not a branch name.", sheet.error)
        assertEquals("--oops", sheet.branch)
        assertFalse(sheet.state!!.running)
    }

    @Test fun `stop asks the machine to stop, and closing the sheet stops only the reading`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.state }
        flow.start()
        eventually { flow.sheet.value?.state?.takeIf { it.running } }

        flow.stop()
        eventually { flow.sheet.value?.state?.takeIf { it.stopping } }
        assertTrue(posts.last().stop)

        flow.dismiss()
        val before = reads.get()
        Thread.sleep(AiReviewFlow.POLL_MS + 500)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("no reads for a closed sheet", before, reads.get())
        assertNull(flow.sheet.value)
        assertTrue("the review itself is the machine's", machine.running)
    }

    @Test fun `the wire drops a priority and a confidence out of range`() {
        val decoded = MobileAiReviewFinding.fromJson(
            MobileProtocol.parseObject("""{"title":"t","body":"b","priority":7,"confidence":140,"path":"a.kt","startLine":5,"endLine":2}""")!!,
        )!!
        assertNull(decoded.priority)
        assertNull(decoded.confidence)
        assertEquals("Unranked", decoded.priorityLabel)
        assertEquals("a.kt:5", decoded.locationLabel)
    }

    private fun finish() {
        machine = machine.copy(
            running = false,
            report = MobileAiReviewReport(
                findings = listOf(MobileAiReviewFinding("Divides by zero", "body", 0, 80, "src/Calc.kt", 2, 3)),
                summary = "1 finding.",
                correctness = "patch is incorrect",
            ),
        )
    }

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 8_000
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
                if (!url.path.endsWith("/" + MobileAiReviewRequest.SUFFIX)) return 404
                if (body.size() == 0) {
                    reads.incrementAndGet()
                    answer = machine.toJson().toString()
                    return 200
                }
                val request = MobileAiReviewRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                posts += request
                val refused = refuseWith
                when {
                    request.stop -> machine = machine.copy(stopping = true)
                    refused != null -> Unit
                    !machine.running -> machine = machine.copy(running = true, report = null, target = "Changes against ${request.branch}")
                }
                if (dropNextPost) {
                    dropNextPost = false
                    throw IOException("connection reset")
                }
                answer = machine.copy(refused = refused).toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "codex:/repo:thread"
    }
}
