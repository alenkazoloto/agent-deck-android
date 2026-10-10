package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * The drain as a *composition*, not as a value: which write wakes it, what it delivers next, and
 * what it must never deliver twice.
 *
 * `OutgoingQueueTest` proves the rules; this proves the view model applies them. Every case here
 * is a defect that was invisible to both the value tests and the plugin's wiring test, because
 * each one is about a call that is or is not made — a queue write that does not restart the loop
 * strands the instruction behind it forever, and no assertion about the list can see that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutgoingDrainTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore

    /** Every prompt this fake machine was actually asked to run, in the order it was asked. */
    private val delivered = mutableListOf<String>()

    /** Prompts the fake machine refuses; everything else is accepted. */
    private var refuse: (String) -> Boolean = { false }

    /** Attachment ids each delivered send named, flattened in delivery order. */
    private val attachmentIds = mutableListOf<String>()

    private var staged = 0

    /** The fake machine's `send-dedupe` table and the instance that owns it; null = no dedupe. */
    private var hostInstance: String? = null
    private val acceptedIds = mutableSetOf<String>()

    /** Prompts whose run starts on the machine and whose answer never reaches the phone. */
    private var loseAck: (String) -> Boolean = { false }

    @Before fun setUp() {
        // Unconfined rather than a test dispatcher: the delivery itself hops to `Dispatchers.IO`,
        // which virtual time cannot advance, so this test waits on the real outcome instead.
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
    }

    @After fun tearDown() {
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a queued send is delivered once the drain runs`() = runBlocking {
        val model = model()
        model.send(TARGET, "run the tests", stopFirst = false)
        settle()

        assertEquals(listOf("run the tests"), delivered)
        assertTrue("a delivered send stayed in the queue", model.state.value.outgoing.isEmpty)
        assertTrue("the draft outlived the send", model.draft(TARGET.key).isEmpty())
    }

    /**
     * The head-of-line rule's other half. A refused prompt parks and holds its lane, so the one
     * typed after it waits — and discarding the refused one is what releases it. Before the fix
     * the discard wrote the file and stopped there: nothing restarted the loop, so the second
     * instruction sat undelivered until the next send, reconnect or launch.
     */
    @Test fun `discarding a parked head delivers the instruction queued behind it`() = runBlocking {
        refuse = { it == "first" }
        val model = model()
        model.send(TARGET, "first", stopFirst = false)
        settle()
        model.send(TARGET, "second", stopFirst = false)
        settle()

        assertEquals("a prompt overtook the parked one in its own lane", emptyList<String>(), delivered)
        val parked = model.state.value.outgoing.items.first()
        assertTrue(parked.parked)

        model.discardQueued(parked.clientMessageId)
        settle()

        assertEquals(listOf("second"), delivered)
    }

    /**
     * The desk's Retry for a failed turn goes through the same durable queue as a typed message, and
     * it is not the composer's Send: what the reader was typing for something else stays put.
     */
    @Test fun `retrying a failed turn sends the machine's words and leaves the draft alone`() = runBlocking {
        val model = model()
        model.editDraft(TARGET.key, "and also the parser")
        model.retryFailedTurn(TARGET, "Continue where you left off.")
        settle()

        assertEquals(listOf("Continue where you left off."), delivered)
        assertTrue("a delivered retry stayed in the queue", model.state.value.outgoing.isEmpty)
        assertEquals("and also the parser", model.draft(TARGET.key))
    }

    /** Edit is the same release, and it owes the reader their text back. */
    @Test fun `editing a parked send returns its text to the composer`() = runBlocking {
        refuse = { true }
        val model = model()
        model.send(TARGET, "check the parser", stopFirst = false)
        settle()

        val parked = model.state.value.outgoing.items.single()
        model.editQueued(parked.clientMessageId)
        settle()

        assertTrue("the prompt was lost between the queue and the composer",
            model.draft(TARGET.key).contains("check the parser"))
        assertTrue(model.state.value.outgoing.isEmpty)
    }

    /**
     * `RULES.md` W5. The old guard collapsed any send whose prompt matched one already queued,
     * which — now that queued rows outlive their request — declined a repeated instruction
     * silently and indefinitely.
     */
    @Test fun `the same sentence may be sent again while an earlier copy is parked`() = runBlocking {
        refuse = { it == "continue" && delivered.isEmpty() }
        val model = model()
        model.send(TARGET, "continue", stopFirst = false)
        settle()
        assertTrue(model.state.value.outgoing.items.single().parked)

        model.send(TARGET, "continue", stopFirst = false)
        settle()

        assertEquals("the second instruction was refused without a word", 2, model.state.value.outgoing.items.size)
    }

    /**
     * M6. The composer enables Send over a photo with no typed text, so the view model has to
     * accept that tap: the old `if (prompt.isBlank()) return` made the button live and the
     * gesture dead, which is the silent decline `RULES.md` W5 forbids.
     */
    @Test fun `a photo with no typed text is a send`() = runBlocking {
        val model = model()
        model.attachPhoto(TARGET.key, jpeg())
        awaitPhoto(model)

        model.send(TARGET, "", stopFirst = false)
        settle()

        assertEquals(listOf(""), delivered)
        assertEquals(listOf("attachment-1"), attachmentIds)
        assertTrue("the chip outlived the send", model.state.value.pendingPhotos[TARGET.key].isNullOrEmpty())
    }

    /** The ids are persisted with the item, so the one path that takes it back owes them too. */
    @Test fun `editing a parked send returns its photos with its text`() = runBlocking {
        refuse = { true }
        val model = model()
        model.attachPhoto(TARGET.key, jpeg())
        awaitPhoto(model)
        model.send(TARGET, "look", stopFirst = false)
        settle()
        assertTrue("the chip must be consumed by the send before Edit can give it back",
            model.state.value.pendingPhotos[TARGET.key].isNullOrEmpty())

        val parked = model.state.value.outgoing.items.single()
        assertEquals(listOf("attachment-1"), parked.attachmentIds)
        model.editQueued(parked.clientMessageId)
        settle()

        assertEquals(
            "the photo was dropped between the queue and the composer",
            listOf("attachment-1"),
            model.state.value.pendingPhotos[TARGET.key].orEmpty().map { it.attachmentId },
        )
    }

    /** An id names a file on the machine that minted it, so it may not ride a pairing switch. */
    @Test fun `switching machines takes the pending photos with the old one`() = runBlocking {
        store.save(OTHER)
        val model = model()
        model.attachPhoto(TARGET.key, jpeg())
        awaitPhoto(model)

        model.switchMachine(OTHER.id)
        settle()

        assertTrue(
            "a photo staged on one machine was offered to the next",
            model.state.value.pendingPhotos.isEmpty(),
        )
    }

    /** Machine B's queue is loaded by the switch, and the switch is what must run it. */
    @Test fun `switching machines drains the queue that came with it`() = runBlocking {
        store.save(OTHER)
        store.saveOutgoing(
            OTHER.id,
            dev.agentdeck.companion.data.OutgoingQueue(
                listOf(
                    dev.agentdeck.companion.data.OutgoingSend(
                        clientMessageId = "owed",
                        key = TARGET.key,
                        projectPath = TARGET.projectPath,
                        vendor = AgentVendor.CLAUDE,
                        label = TARGET.title,
                        prompt = "left over from the outage",
                    ),
                ),
            ),
        )
        store.activate(MACHINE.id)
        val model = model()
        settle()
        delivered.clear()

        model.switchMachine(OTHER.id)
        settle()

        assertEquals(listOf("left over from the outage"), delivered)
    }

    /**
     * M2 lost ack + host restart. The first attempt ran on the machine, the app died before the
     * ack, and the IDE then restarted: it no longer knows the id, so resuming the send would run
     * the prompt twice. Before the fix every hello advertising `send-dedupe` resumed it.
     */
    @Test fun `an interrupted send stays parked when the machine has restarted since`() = runBlocking {
        hostInstance = "ide-2"
        seedInterrupted(ranOn = "ide-1")
        val model = model()
        model.refreshHello()
        settle()

        assertEquals("the prompt ran a second time", emptyList<String>(), delivered)
        val row = model.state.value.outgoing.items.single()
        assertTrue("a restarted machine's hello un-parked an uncertain send", row.parked)
        assertTrue(row.uncertain)
    }

    @Test fun `an interrupted send resumes into the same machine, which collapses the repeat`() = runBlocking {
        hostInstance = "ide-1"
        seedInterrupted(ranOn = "ide-1")
        val model = model()
        model.refreshHello()
        settle()

        assertEquals(emptyList<String>(), delivered)
        assertTrue("the collapsed repeat stayed queued", model.state.value.outgoing.isEmpty)
    }

    @Test fun `the reader's Retry after a restart runs the prompt`() = runBlocking {
        hostInstance = "ide-2"
        seedInterrupted(ranOn = "ide-1")
        val model = model()
        model.refreshHello()
        settle()

        model.retryQueued("owed")
        settle()

        assertEquals(listOf("left over from the outage"), delivered)
        assertTrue(model.state.value.outgoing.isEmpty)
    }

    /**
     * The same restart on a live link: the ack is lost, the item waits out its backoff, and the
     * IDE restarts meanwhile. The automatic retry names the instance it reached, so the new one
     * parks it. Before the fix the retry went out unguarded and ran the prompt a second time.
     */
    @Test fun `a lost ack retried into a restarted machine parks instead of running twice`() = runBlocking {
        hostInstance = "ide-1"
        val model = model()
        model.refreshHello()
        settle()
        loseAck = { true }
        model.send(TARGET, "run the tests", stopFirst = false)
        awaitDelivered(1)
        loseAck = { false }
        hostInstance = "ide-2"
        acceptedIds.clear()
        settle(timeoutMs = 15_000)

        assertEquals("the prompt ran a second time", listOf("run the tests"), delivered)
        val row = model.state.value.outgoing.items.single()
        assertTrue(row.parked)
        assertTrue(row.uncertain)
    }

    private fun awaitDelivered(n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (delivered.size < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        assertEquals(n, delivered.size)
        // Let the failed attempt write its backoff back before the machine "restarts".
        while (current?.state?.value?.outgoing?.items?.any { it.inFlight } == true &&
            System.currentTimeMillis() < deadline + 5_000
        ) {
            Thread.sleep(5)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }

    /** What the disk holds after the app died mid-attempt: in flight, sent to [ranOn], already run there. */
    private fun seedInterrupted(ranOn: String) {
        // A restarted machine has forgotten the ids its previous instance accepted.
        if (ranOn == hostInstance) acceptedIds += "owed"
        store.saveOutgoing(
            MACHINE.id,
            dev.agentdeck.companion.data.OutgoingQueue(
                listOf(
                    dev.agentdeck.companion.data.OutgoingQueue.attempting(
                        dev.agentdeck.companion.data.OutgoingSend(
                            clientMessageId = "owed", key = TARGET.key, projectPath = TARGET.projectPath,
                            vendor = AgentVendor.CLAUDE, label = TARGET.title, prompt = "left over from the outage",
                        ),
                        ranOn,
                    ),
                ),
            ),
        )
    }

    // ---- harness -------------------------------------------------------------------------

    /**
     * Waits for the drain to go quiet.
     *
     * Real time, not virtual: the delivery crosses `Dispatchers.IO`, and a test dispatcher can
     * only advance the coroutines it owns. The fake socket answers from memory, so this settles
     * in milliseconds; the timeout is there to fail loudly rather than hang.
     */
    private fun settle(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (quiet()) return
        }
        throw AssertionError("the drain never went quiet")
    }

    private fun quiet(): Boolean {
        val model = current ?: return true
        val state = model.state.value
        return state.delivering == null &&
            state.outgoing.nextWakeMs(System.currentTimeMillis()) == null
    }

    private var current: DeckViewModel? = null

    private fun model() = DeckViewModel(app)
        .also { it.connectionForTest = { fakeBridge() }; current = it }

    /**
     * A real [BridgeClient] over a fake socket, so the request the app builds is the one this
     * records — including its `clientMessageId`, which is what a retry is judged by.
     */
    private fun fakeBridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = body
            override fun getResponseCode(): Int {
                if (!url.path.endsWith("/v1/send")) return 200
                return if (refuse(promptOf()) || unconfirmed()) 409 else 200
            }
            override fun getErrorStream(): InputStream = (
                if (unconfirmed()) """{"v":1,"error":"send-unconfirmed","message":"The IDE restarted."}"""
                else """{"v":1,"error":"spend-limit","message":"This chat has reached its spending limit."}"""
                ).byteInputStream()
            override fun getInputStream(): InputStream {
                if (url.path.endsWith("/v1/hello")) {
                    val instance = hostInstance ?: return "{}".byteInputStream()
                    return """{"v":1,"capabilities":["send","send-dedupe"],"sendInstance":"$instance"}"""
                        .byteInputStream()
                }
                if (url.path.endsWith(com.github.claudeagents.core.mobile.MobileAttachment.ROUTE)) {
                    staged++
                    return """{"v":1,"attachmentId":"attachment-$staged","bytes":${body.size()}}"""
                        .byteInputStream()
                }
                if (!url.path.endsWith("/v1/send")) return "{}".byteInputStream()
                val prompt = promptOf()
                if (refuse(prompt) || unconfirmed()) throw IOException("refused")
                val id = field("clientMessageId")
                if (hostInstance != null && id != null && !acceptedIds.add(id)) {
                    return """{"v":1,"taskId":"task-dup","state":"queued"}""".byteInputStream()
                }
                delivered += prompt
                if (loseAck(prompt)) throw IOException("connection reset")
                com.github.claudeagents.core.mobile.MobileProtocol
                    .parseObject(body.toString(Charsets.UTF_8))
                    ?.getAsJsonArray("attachmentIds")
                    ?.forEach { attachmentIds += it.asString }
                return """{"v":1,"taskId":"task-${delivered.size}","state":"queued"}""".byteInputStream()
            }

            private fun promptOf(): String = field("prompt").orEmpty()

            private fun field(name: String): String? = com.github.claudeagents.core.mobile.MobileProtocol
                .parseObject(body.toString(Charsets.UTF_8))
                ?.get(name)?.asString

            /** The real host's rule: an id it does not know, retried from another instance. */
            private fun unconfirmed(): Boolean {
                val retryOf = field("retryOf") ?: return false
                return hostInstance != null && retryOf != hostInstance && field("clientMessageId") !in acceptedIds
            }
        }
    }

    /**
     * Waits for the upload behind a pick.
     *
     * [settle] watches the *drain*, and an attachment is a separate coroutine that crosses
     * `Dispatchers.Default` and `Dispatchers.IO` — under a loaded suite it had not finished by
     * the time the assertion ran, which made the case pass alone and fail in the whole run.
     */
    private fun awaitPhoto(model: DeckViewModel, key: String = TARGET.key) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (model.state.value.pendingPhotos[key].orEmpty().isNotEmpty()) return
            Thread.sleep(5)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        throw AssertionError("the photo was never staged: ${model.state.value.snack?.message}")
    }

    /** A real JPEG: `PhotoEncoder` decodes before it compresses, so random bytes never upload. */
    private fun jpeg(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream()
            .also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
        val OTHER = MACHINE.copy(machineName = "laptop", deviceId = "device-2")
        val TARGET = Screen.Conversation(
            key = "claude:/repo:session", title = "Fix the parser",
            vendor = AgentVendor.CLAUDE, projectPath = "/repo",
        )
    }
}
