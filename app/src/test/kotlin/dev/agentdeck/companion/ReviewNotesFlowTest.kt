package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileReviewNote
import com.github.claudeagents.core.mobile.MobileReviewNoteRequest
import com.github.claudeagents.core.mobile.MobileReviewNotes
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.NoteLine
import dev.agentdeck.companion.data.ReviewNotesFlow
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
import java.net.SocketTimeoutException
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

/**
 * Review notes from the Changes tab (M4, P21): a note names the exact lines the reader pressed
 * and the text the diff showed there, an attach puts the machine's token in that chat's
 * composer, a write whose answer never arrived is tried again under the same id, and the text
 * of a note survives a closed or failed editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewNotesFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val snacks = CopyOnWriteArrayList<String>()
    private val attached = CopyOnWriteArrayList<Pair<String, String>>()
    private val sent = CopyOnWriteArrayList<MobileReviewNoteRequest>()
    /** Each POST's answer: a status and body, or null for an answer that never arrives. */
    private val answers = ArrayDeque<Pair<Int, String>?>()
    @Volatile private var listed = MobileReviewNotes(KEY, listOf(NOTE))

    private val flow = ReviewNotesFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, { snacks += it }) { key, token ->
        attached += key to token
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `a long-pressed line widened by Next line is saved as those lines and that text`() {
        flow.startAdd(KEY, "src/A.kt", MobileReviewNote.CURRENT, NoteLine(12, "val a = 1"), listOf(NoteLine(13, "val b = 2")))
        flow.extend()
        flow.editBody("Both should be constants.")
        answers += 200 to MobileReviewNotes(KEY, listOf(NOTE), noteId = "n1").toJson().toString()
        flow.save()
        eventually { snacks.firstOrNull() }

        val request = sent.single()
        assertEquals(MobileReviewNoteRequest.ADD, request.op)
        assertEquals("src/A.kt", request.path)
        assertEquals(12 to 13, request.startLine to request.endLine)
        assertEquals("val a = 1\nval b = 2", request.quote)
        assertEquals("Both should be constants.", request.body)
        assertNull(flow.state.value?.editor)
    }

    @Test fun `a refused save keeps the editor open with its text under the machine's reason`() {
        flow.startAdd(KEY, "src/A.kt", MobileReviewNote.CURRENT, NoteLine(12, "val a = 1"), emptyList())
        flow.editBody("Keep me.")
        answers += MobileRefusal.REVIEW_NOTE_LINES_MOVED.status to MobileRefusal.REVIEW_NOTE_LINES_MOVED.toJson().toString()
        flow.save()

        val editor = eventually { flow.state.value?.editor?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.REVIEW_NOTE_LINES_MOVED.message, editor.error)
        assertEquals("Keep me.", editor.body)
    }

    @Test fun `a closed editor gives its text back when the same line is pressed again`() {
        flow.startAdd(KEY, "src/A.kt", MobileReviewNote.CURRENT, NoteLine(12, "val a = 1"), emptyList())
        flow.editBody("Half a thought")
        flow.cancelEditor()
        flow.startAdd(KEY, "src/A.kt", MobileReviewNote.CURRENT, NoteLine(12, "val a = 1"), emptyList())

        assertEquals("Half a thought", flow.state.value?.editor?.body)
    }

    @Test fun `attach sends the ticked open notes and hands the token to the chat's composer`() {
        flow.openList(KEY)
        eventually { flow.state.value?.notes }
        flow.toggle("n1")
        answers += 200 to MobileReviewNotes(KEY, listOf(NOTE), token = "@review-notes:b1").toJson().toString()
        flow.attach()
        eventually { attached.firstOrNull() }

        assertEquals(listOf("n1"), sent.single().noteIds)
        assertEquals(KEY to "@review-notes:b1", attached.single())
        assertEquals(false, flow.state.value?.listing)
        assertTrue(snacks.single().startsWith("1 review note attached"))
    }

    @Test fun `a resolved note cannot be ticked for attaching`() {
        listed = MobileReviewNotes(KEY, listOf(NOTE.copy(state = MobileReviewNote.RESOLVED)))
        flow.openList(KEY)
        eventually { flow.state.value?.notes }

        flow.toggle("n1")

        assertTrue(flow.state.value!!.selected.isEmpty())
    }

    @Test fun `a write whose answer never arrived is retried as the same operation`() {
        flow.startAdd(KEY, "src/A.kt", MobileReviewNote.CURRENT, NoteLine(12, "val a = 1"), emptyList())
        flow.editBody("Once only.")
        answers += null
        flow.save()
        eventually { flow.state.value?.editor?.error }
        answers += 200 to MobileReviewNotes(KEY, listOf(NOTE), noteId = "n1").toJson().toString()
        flow.save()
        eventually { snacks.firstOrNull() }
        flow.startAdd(KEY, "src/A.kt", MobileReviewNote.CURRENT, NoteLine(12, "val a = 1"), emptyList())
        flow.editBody("Once only.")
        answers += 200 to MobileReviewNotes(KEY, listOf(NOTE), noteId = "n2").toJson().toString()
        flow.save()
        eventually { sent.takeIf { it.size == 3 } }

        assertEquals("a retry after no answer would save the note twice", sent[0].operationId, sent[1].operationId)
        assertNotEquals("a definite answer did not retire the id", sent[1].operationId, sent[2].operationId)
    }

    @Test fun `a state this client does not know is never offered for attaching`() {
        val decoded = MobileReviewNote.fromJson(MobileProtocol.parseObject("""{"id":"x","state":"archived"}""")!!)
        assertEquals(MobileReviewNote.RESOLVED, decoded.state)
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
                if (!url.path.endsWith("/notes")) return 404
                if (body.size() == 0) {
                    answer = listed.toJson().toString()
                    return 200
                }
                sent += MobileReviewNoteRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                val (status, text) = answers.removeFirst() ?: throw SocketTimeoutException("no answer")
                answer = text
                return status
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude:/repo:session"
        val NOTE = MobileReviewNote("n1", "src/A.kt", MobileReviewNote.CURRENT, 12, 12, "val a = 1", "Why?", MobileReviewNote.OPEN)
    }
}
