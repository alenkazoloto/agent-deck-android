package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import dev.agentdeck.companion.data.BridgeClient
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

/**
 * Pin, Done, reopen and rename from a fleet row (PLAN-MOBILE-REDESIGN M3). The row takes the
 * machine's answer rather than the tap's assumption — Done drops a pin on the desk, so a phone that
 * flipped one flag would draw a state neither list holds — and Done's Undo puts that pin back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionActionsTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    /** The machine's own stores, as `/v1/session-actions` and `/v1/fleet` read them. */
    @Volatile private var pinned = true
    @Volatile private var done = false
    @Volatile private var title = "Fix the parser"
    @Volatile private var refusal: String? = null
    private val posted = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
        model.refreshFleet(initial = true)
        eventually { model.state.value.snapshot?.rows?.isNotEmpty() == true }
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `done takes the machine's answer, drops the pin, and Undo restores both`() {
        model.sessionAction(row(), MobileSessionActionRequest.DONE)
        eventually { row().done }
        assertFalse("the desk dropped the pin, and the row says so", row().pinned)
        assertEquals("Marked \"Fix the parser\" done", model.state.value.snack?.message)
        assertEquals("Undo", model.state.value.snack?.undoLabel)

        model.undoSnack()
        eventually { !row().done && row().pinned }
        assertEquals("one request: the pin the Done took, which reopens it too", listOf("done", "pin"), posted.toList())
    }

    @Test fun `a rename shows the name the machine stored`() {
        model.sessionAction(row(), MobileSessionActionRequest.RENAME, "  Parser rewrite ")
        // The row takes the answer before the snack is raised, so wait for both.
        eventually { row().title == "Parser rewrite" && model.state.value.snack != null }
        assertEquals("Renamed to \"Parser rewrite\"", model.state.value.snack?.message)
    }

    @Test fun `a refusal is the machine's own sentence and changes nothing`() {
        refusal = "Codex could not rename this conversation. Try again, or rename it in the IDE."
        model.sessionAction(row(), MobileSessionActionRequest.RENAME, "New")
        eventually { model.state.value.snack != null }
        assertEquals(refusal, model.state.value.snack?.message)
        assertEquals("Fix the parser", row().title)
    }

    private fun row(): MobileFleetRow = model.state.value.snapshot!!.rows.single { it.key == KEY }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    private fun fleetRow() = MobileFleetRow(
        key = KEY, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        pinned = pinned, done = done,
    )

    private inner class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "test-machine"
        override fun fleet() = MobileFleetSnapshot(listOf(fleetRow()), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
    }

    /** The machine: applies the write as `MobileSessionActions` does, then answers its stores. */
    private fun apply(body: String): Pair<Int, String> {
        refusal?.let { return 409 to """{"v":1,"error":"rename-failed","message":"$it"}""" }
        val request = MobileSessionActionRequest.fromJson(MobileProtocol.parseObject(body)!!)
        posted += request.action
        when (request.action) {
            MobileSessionActionRequest.PIN -> { pinned = true; done = false }
            MobileSessionActionRequest.UNPIN -> pinned = false
            MobileSessionActionRequest.DONE -> { done = true; pinned = false }
            MobileSessionActionRequest.REOPEN -> done = false
            MobileSessionActionRequest.RENAME -> title = request.title.orEmpty().trim()
        }
        return 200 to """{"v":1,"key":"$KEY","title":"$title","pinned":$pinned,"done":$done}"""
    }

    private fun bridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private val answer by lazy {
                if (url.path == MobileSessionActionRequest.ROUTE) apply(body.toString(Charsets.UTF_8.name())) else 200 to "{}"
            }
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = body
            override fun getResponseCode(): Int = answer.first
            override fun getErrorStream(): InputStream = answer.second.byteInputStream()
            override fun getInputStream(): InputStream = answer.second.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude:/repo:session"
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
