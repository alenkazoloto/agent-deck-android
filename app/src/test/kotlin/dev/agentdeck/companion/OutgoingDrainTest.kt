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

    // ---- harness -------------------------------------------------------------------------

    /**
     * Waits for the drain to go quiet.
     *
     * Real time, not virtual: the delivery crosses `Dispatchers.IO`, and a test dispatcher can
     * only advance the coroutines it owns. The fake socket answers from memory, so this settles
     * in milliseconds; the timeout is there to fail loudly rather than hang.
     */
    private fun settle() {
        val deadline = System.currentTimeMillis() + 5_000
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
                return if (refuse(promptOf())) 409 else 200
            }
            override fun getErrorStream(): InputStream =
                """{"v":1,"error":"spend-limit","message":"This chat has reached its spending limit."}"""
                    .byteInputStream()
            override fun getInputStream(): InputStream {
                if (!url.path.endsWith("/v1/send")) return "{}".byteInputStream()
                val prompt = promptOf()
                if (refuse(prompt)) throw IOException("refused")
                delivered += prompt
                return """{"v":1,"taskId":"task-${delivered.size}","state":"queued"}""".byteInputStream()
            }

            private fun promptOf(): String = com.github.claudeagents.core.mobile.MobileProtocol
                .parseObject(body.toString(Charsets.UTF_8))
                ?.get("prompt")?.asString.orEmpty()
        }
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
