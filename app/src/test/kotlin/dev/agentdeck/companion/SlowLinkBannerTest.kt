package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * t3code #2750/#2761: on a slow link the banner flipped between "Connecting…" and "<machine> is
 * not answering" while the event stream was fine, and each flip re-read the open conversation.
 *
 * Two halves, both through the real view model and the real [LiveLink]. One conversation read
 * that times out or is refused says nothing about the stream — it belongs to the conversation as
 * its notice — and a keep-alive still arriving is proof enough to withdraw a banner that guessed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlowLinkBannerTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink

    /** How the fake machine fails `/v1/session/{key}`: refuses it, or (null) never answers. */
    private var sessionRefusal: BridgeRefusal? = null

    /** Whether `/v1/session/{key}` fails at all; false answers a page. */
    private var sessionFails = false

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
    }

    /** View models made here; each one observes the process-wide link, so none may outlive its test. */
    private val models = mutableListOf<DeckViewModel>()

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a refused conversation read is the conversation's notice, not a banner on every screen`() {
        val model = opened(refusal = BridgeRefusal(404, "not-found", "That conversation is no longer on this machine."))

        assertEquals("That conversation is no longer on this machine.", model.state.value.notice)
        assertEquals("a refused read moved the link banner", Link.Live, live.link.value)
    }

    @Test fun `a conversation read that cannot reach the machine leaves the link banner alone`() {
        val model = opened(refusal = null)

        assertEquals("Could not reach this machine. The IDE has to be running.", model.state.value.notice)
        assertEquals("a timed-out read moved the link banner", Link.Live, live.link.value)
    }

    @Test fun `the next good read takes the failure notice down`() {
        val model = opened(refusal = null)
        assertEquals("Could not reach this machine. The IDE has to be running.", model.state.value.notice)
        sessionFails = false

        model.loadTranscript(TARGET.key, quiet = true)
        settle(model)

        assertNull("the failure notice outlived the read that healed it", model.state.value.notice)
    }

    @Test fun `a keep-alive from a stream that is still delivering withdraws a guessed banner`() {
        val stream = FakeStream()
        live.connectionForTest = { stream }
        live.bind(MACHINE)
        assertTrue("the first frame never proved the stream", stream.started.await(5, TimeUnit.SECONDS))
        eventually { live.link.value == Link.Live }

        live.failLink(BridgeRefusal(404, "not-found", "That conversation is no longer on this machine."))
        assertTrue(live.link.value is Link.Stale)

        stream.alive()
        assertEquals("a keep-alive left the banner up", Link.Live, live.link.value)
    }

    @Test fun `a keep-alive does not undo a verdict the link cannot heal`() {
        val stream = FakeStream()
        live.connectionForTest = { stream }
        live.bind(MACHINE)
        assertTrue(stream.started.await(5, TimeUnit.SECONDS))
        eventually { live.link.value == Link.Live }

        live.failLink(BridgeRefusal(401, "unauthorized", "This device is not paired with this machine."))
        stream.alive()

        assertTrue("a keep-alive covered a revoked pairing", live.link.value is Link.Repair)
    }

    // ---- harness ---------------------------------------------------------------------------

    /** A conversation opened over a live stream while `/v1/session/…` fails, and the read settled. */
    private fun opened(refusal: BridgeRefusal?): DeckViewModel {
        sessionFails = true
        sessionRefusal = refusal
        live.connectionForTest = { FakeStream() }
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() }; models += it }
        eventually { live.link.value == Link.Live }
        model.openConversation(row())
        settle(model)
        return model
    }

    private fun settle(model: DeckViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (!model.state.value.transcriptLoading && !model.state.value.transcriptRefreshing) return
        }
        throw AssertionError("the transcript read never settled")
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    /** Delivers one keep-alive on connect, then holds the socket open and lets the test send more. */
    private class FakeStream : LinkConnection {
        val started = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        @Volatile private var onAlive: () -> Unit = {}

        override val lastGoodHost = "test-machine"
        override fun fleet() = MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            this.onAlive = onAlive
            onAlive()
            started.countDown()
            closed.await()
        }
        override fun close() = closed.countDown()
        fun alive() = onAlive()
    }

    private fun row() = com.github.claudeagents.core.mobile.MobileFleetRow(
        key = TARGET.key, vendor = AgentVendor.CLAUDE, accountId = "default",
        projectPath = TARGET.projectPath, projectName = "repo", gitBranch = null,
        title = TARGET.title, attention = null, waitingReason = null, lastActivityMs = 1,
        costUsd = 0.0, costKnown = false, contextPct = null, messageCount = 1,
    )

    private fun page() =
        """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[""" +
            """{"id":"t0","role":"assistant","text":"hello","timestampMs":1}],""" +
            """"hasMore":false,"costUsd":0.0,"costKnown":false,"running":false,"generatedAtMs":1}"""

    private fun fakeBridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private val failing get() = sessionFails && url.path.startsWith("/v1/session/")
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = body
            override fun getResponseCode(): Int {
                if (!failing) return 200
                sessionRefusal?.let { return it.status }
                throw SocketTimeoutException("read timed out")
            }
            override fun getErrorStream(): InputStream = sessionRefusal
                ?.let { """{"v":1,"error":"${it.code}","message":"${it.message}"}""" }
                .orEmpty().ifEmpty { "{}" }.byteInputStream()
            override fun getInputStream(): InputStream =
                (if (url.path.startsWith("/v1/session/")) page() else "{}").byteInputStream()
        }
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
        val TARGET = Screen.Conversation(
            key = "claude:/repo:session", title = "Fix the parser",
            vendor = AgentVendor.CLAUDE, projectPath = "/repo",
        )
    }
}
