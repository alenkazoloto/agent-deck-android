package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import com.github.claudeagents.core.mobile.MobileSessionActionResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.FleetGrouping
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PinOrder
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

/**
 * The desk's "Move pin up/down" and "Regenerate title" through the view model (M3, P05): a move
 * sends the whole wanted order of the painted pins and the list re-sorts at once; an end sends
 * nothing and says the desk's sentence; a retitle names the row with the machine's title, and a
 * refusal is said, not drawn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinOrderRetitleFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    private val asked = CopyOnWriteArrayList<MobileSessionActionRequest>()
    @Volatile private var refusal: MobileRefusal? = null
    /** The read times out after the body went out, as a model slower than the phone's read does. */
    @Volatile private var readTimesOut = false

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
        eventually { model.state.value.snapshot?.rows?.takeIf { it.isNotEmpty() } }
        eventually { model.state.value.link.takeIf { it == Link.Live } }
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `pins are listed in the desk's order, not by recency`() {
        val flat = FleetGrouping.sections(model.state.value.snapshot!!.rows, generatedAtMs = 10, sort = FleetSort.RECENT).single().rows
        assertEquals(listOf(A.key, B.key, PLAIN.key), flat.map { it.key })
    }

    @Test fun `moving a pin down sends the painted pins' new order and re-sorts at once`() {
        model.movePin(A, listOf(A.key, B.key), delta = 1)
        eventually { asked.singleOrNull() }

        assertEquals(
            MobileSessionActionRequest(A.key, MobileSessionActionRequest.PIN_ORDER, pinned = listOf(B.key, A.key)),
            asked.single(),
        )
        val ranks = eventually {
            model.state.value.snapshot!!.rows.associate { it.key to it.pinRank }.takeIf { it[B.key] == 0 }
        }
        assertEquals("the moved pins swap the rank slots they held", mapOf(A.key to 3, B.key to 0, PLAIN.key to null), ranks)
    }

    @Test fun `a pin already at the top sends nothing and says so`() {
        model.movePin(A, listOf(A.key, B.key), delta = -1)
        eventually { model.state.value.snack?.message?.takeIf { it == PinOrder.endMessage(-1) } }
        assertTrue(asked.isEmpty())
    }

    @Test fun `a stale pin list is refused by name and nothing moves`() {
        refusal = MobileRefusal.PIN_ORDER_STALE
        model.movePin(B, listOf(A.key, B.key), delta = -1)
        eventually { model.state.value.snack?.message?.takeIf { it == MobileRefusal.PIN_ORDER_STALE.message } }
        assertEquals(0, model.state.value.snapshot!!.rows.first { it.key == A.key }.pinRank)
    }

    @Test fun `a retitle names the row with the machine's title`() {
        model.retitle(PLAIN)
        eventually { model.state.value.snack?.message?.takeIf { it == "Renamed to \"Port the parser\"" } }
        assertEquals(MobileSessionActionRequest(PLAIN.key, MobileSessionActionRequest.RETITLE), asked.single())
        assertEquals("Port the parser", model.state.value.snapshot!!.rows.first { it.key == PLAIN.key }.title)
    }

    @Test fun `a one-message chat's retitle refusal is said and the name kept`() {
        refusal = MobileRefusal.RETITLE_ONE_MESSAGE
        model.retitle(PLAIN)
        eventually { model.state.value.snack?.message?.takeIf { it == MobileRefusal.RETITLE_ONE_MESSAGE.message } }
        assertEquals(PLAIN.title, model.state.value.snapshot!!.rows.first { it.key == PLAIN.key }.title)
    }

    @Test fun `a retitle the phone stopped waiting for says the machine is still writing`() {
        readTimesOut = true
        model.retitle(PLAIN)
        eventually { model.state.value.snack?.message?.takeIf { it == MobileRefusal.RETITLE_UNCONFIRMED.message } }
        assertEquals("asked once: another address would start a second paid generation", 1, asked.size)
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

    private inner class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "a.test"
        override fun fleet() = MobileFleetSnapshot(listOf(PLAIN, B, A), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
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
                if (url.path != MobileSessionActionRequest.ROUTE) return 404
                val request = MobileSessionActionRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                asked += request
                if (readTimesOut) throw java.net.SocketTimeoutException("Read timed out")
                refusal?.let {
                    answer = it.toJson().toString()
                    return it.status
                }
                answer = when (request.action) {
                    MobileSessionActionRequest.RETITLE -> MobileSessionActionResult(request.key, "Port the parser", false, false)
                    else -> MobileSessionActionResult(request.key, "", true, false, pinRank = 3)
                }.toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        fun row(key: String, title: String, lastActivityMs: Long, pinRank: Int?) = MobileFleetRow(
            key = "claude:/repo:$key", vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
            projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
            lastActivityMs = lastActivityMs, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 4,
            pinned = pinRank != null, pinRank = pinRank,
        )
        // A is the desk's first pin though B is newer: the order is the desk's, not recency's.
        val A = row("a", "Alpha", lastActivityMs = 1, pinRank = 0)
        val B = row("b", "Beta", lastActivityMs = 5, pinRank = 3)
        val PLAIN = row("c", "Gamma", lastActivityMs = 9, pinRank = null)
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("a.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
