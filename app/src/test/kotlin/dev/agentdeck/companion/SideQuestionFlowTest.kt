package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileSideAsk
import com.github.claudeagents.core.mobile.MobileSideQuestionQuery
import com.github.claudeagents.core.mobile.MobileSideQuestionRequest
import com.github.claudeagents.core.mobile.MobileSideQuestionState
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SideQuestionFlow
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * `/btw` from the phone: the sheet lists the asks the machine holds, a typed question is a POST that
 * answers at once and is then read with GET until nothing runs, and the reader's text survives every
 * outcome but the machine taking it. A retried question is one ask; closing the sheet stops only the
 * reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SideQuestionFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val posts = CopyOnWriteArrayList<MobileSideQuestionRequest>()
    private val reads = AtomicInteger()
    @Volatile private var held = listOf<MobileSideAsk>()
    /** Reads a running ask takes to finish; `Int.MAX_VALUE` keeps it running. */
    @Volatile private var readsToFinish = 2
    @Volatile private var refuseWith: String? = null
    @Volatile private var postFails = false
    @Volatile private var generation = 0L

    private val flow = SideQuestionFlow(scope, { bridge() }, { generation }, { it.message ?: "error" }, pollMs = 10)

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet opens on the chat and lists what the machine already holds`() {
        held = listOf(MobileSideAsk("a1", "Earlier?", running = false, answer = "Yes."))

        flow.open(KEY, "Fix the parser")

        val sheet = eventually { flow.sheet.value?.takeIf { it.asks.isNotEmpty() } }
        assertEquals("Fix the parser", sheet.title)
        assertEquals("Yes.", sheet.asks.single().answer)
        assertEquals("", sheet.draft)
    }

    @Test fun `a typed question is sent trimmed, clears the field once taken and is answered by reading`() {
        flow.open(KEY, "Fix the parser")
        flow.edit("  Which file?  ")

        flow.ask()

        val running = eventually { flow.sheet.value?.takeIf { it.asks.isNotEmpty() } }
        assertEquals("", running.draft)
        assertEquals("Which file?", posts.single().question)
        val done = eventually { flow.sheet.value?.takeIf { it.asks.isNotEmpty() && !it.running } }
        assertEquals("Answer to Which file?", done.asks.single().answer)
    }

    @Test fun `a typed slash command's question is asked at once`() {
        flow.open(KEY, "Fix the parser", question = "Why so slow?")

        eventually { flow.sheet.value?.takeIf { it.asks.isNotEmpty() } }
        assertEquals("Why so slow?", posts.single().question)
        assertEquals("", flow.sheet.value?.draft)
    }

    @Test fun `a bare command opens on an empty field and sends nothing`() {
        flow.open(KEY, "Fix the parser", question = "  ")
        flow.ask()

        assertTrue(posts.isEmpty())
    }

    @Test fun `a question the machine will not start keeps the text and shows its sentence`() {
        refuseWith = "Other side questions are still being answered on this machine. Ask again when they finish."
        flow.open(KEY, "Fix the parser")
        flow.edit("Why?")

        flow.ask()

        val sheet = eventually { flow.sheet.value?.takeIf { it.refused != null } }
        assertEquals(refuseWith, sheet.refused)
        assertEquals("Why?", sheet.draft)
        flow.edit("Why not?")
        assertNull("the next edit takes the sentence away", flow.sheet.value?.refused)
    }

    @Test fun `a lost link keeps the text and a resend of the same text is the same operation`() {
        postFails = true
        flow.open(KEY, "Fix the parser")
        flow.edit("Why?")
        flow.ask()
        val failed = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals("Why?", failed.draft)

        postFails = false
        flow.ask()
        eventually { flow.sheet.value?.takeIf { it.asks.isNotEmpty() } }
        flow.edit("A different question")
        flow.ask()
        eventually { flow.sheet.value?.takeIf { it.asks.size == 2 } }

        assertEquals(3, posts.size)
        assertEquals("the same text is one ask however often it is sent", posts[0].operationId, posts[1].operationId)
        assertNotEquals("another text is another ask", posts[1].operationId, posts[2].operationId)
    }

    @Test fun `a blank field sends nothing`() {
        flow.open(KEY, "Fix the parser")
        flow.edit("   ")

        flow.ask()

        assertTrue(posts.isEmpty())
    }

    @Test fun `closing the sheet stops the reading, and reopening shows what finished meanwhile`() {
        readsToFinish = Int.MAX_VALUE
        flow.open(KEY, "Fix the parser", question = "Slow one")
        eventually { flow.sheet.value?.takeIf { it.running } }
        flow.dismiss()
        Thread.sleep(60)
        val settled = reads.get()
        Thread.sleep(80)
        assertEquals("nothing reads once the sheet is closed", settled, reads.get())

        held = held.map { it.copy(running = false, answer = "Done meanwhile") }
        flow.open(KEY, "Fix the parser")

        val sheet = eventually { flow.sheet.value?.takeIf { it.asks.isNotEmpty() && !it.running } }
        assertEquals("Done meanwhile", sheet.asks.single().answer)
    }

    @Test fun `a typed question survives closing and reopening the sheet, and a different machine drops it`() {
        flow.open(KEY, "Fix the parser")
        flow.edit("Half a thou")
        flow.dismiss()

        flow.open(KEY, "Fix the parser")
        assertEquals("Half a thou", flow.sheet.value?.draft)

        flow.forget()
        assertNull(flow.sheet.value)
        flow.open(KEY, "Fix the parser")
        assertEquals("", flow.sheet.value?.draft)
    }

    @Test fun `an answer after the sheet moved to another chat paints nothing on it`() {
        readsToFinish = Int.MAX_VALUE
        flow.open(KEY, "First", question = "About first")
        eventually { flow.sheet.value?.takeIf { it.running } }
        held = emptyList()
        flow.open("claude:/repo:other", "Second")
        Thread.sleep(60)

        assertEquals("Second", flow.sheet.value?.title)
        assertTrue(flow.sheet.value?.asks.orEmpty().isEmpty())
    }

    private fun <T : Any> eventually(read: () -> T?): T {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) read()?.let { return it }.also { Thread.sleep(10) }
        throw AssertionError("nothing arrived in 5 s")
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private var answer = "{}"
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileSideQuestionQuery.ROUTE) return 404
                val key: String
                if (requestMethod == "POST") {
                    val request = MobileSideQuestionRequest.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject)
                    key = request.key
                    // Recorded before the link drops: the machine may have taken it, and a resend must say so.
                    posts += request
                    if (postFails) throw IOException("link down")
                    refuseWith?.let {
                        answer = MobileSideQuestionState(key, held, refused = it).toJson().toString()
                        return 200
                    }
                    if (held.none { it.id == request.operationId }) {
                        held = held + MobileSideAsk(request.operationId ?: "op", request.question, running = true)
                        readsLeft = readsToFinish
                    }
                } else {
                    key = java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
                    reads.incrementAndGet()
                    if (held.any { it.running } && --readsLeft <= 0) {
                        held = held.map { if (it.running) it.copy(running = false, answer = "Answer to ${it.question}") else it }
                    }
                }
                answer = MobileSideQuestionState(key, held).toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    @Volatile private var readsLeft = 0

    private companion object {
        const val KEY = "claude:/repo:chat"
    }
}
