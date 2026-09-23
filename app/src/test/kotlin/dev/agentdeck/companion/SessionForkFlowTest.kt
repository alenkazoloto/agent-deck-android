package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileForkPoint
import com.github.claudeagents.core.mobile.MobileSessionForkPoints
import com.github.claudeagents.core.mobile.MobileSessionForkRequest
import com.github.claudeagents.core.mobile.MobileSessionForkResult
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
import org.junit.Assert.assertNull
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
 * Fork through the view model (M3, P06): a Claude chat asks the machine which messages it can fork
 * from and forks at the one picked; a Codex chat forks whole at once. A listed fork opens with the
 * message forked from in its composer; one the machine has not listed yet leaves the reader where
 * they were, told so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionForkFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    /** Each request's body, as the machine received it. */
    private val asked = CopyOnWriteArrayList<MobileSessionForkRequest>()
    @Volatile private var listed = true
    @Volatile private var refused: String? = null
    @Volatile private var unconfirmedOnce = false

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
        eventually { model.state.value.snapshot?.rows?.takeIf { it.isNotEmpty() } }
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a Claude chat forks at the picked message and the fork opens with it in the composer`() {
        model.startFork(ROW)
        val points = eventually { model.state.value.forkPoints }
        assertEquals(listOf("First question", "Second question"), points.points.map { it.label })

        model.forkAt(points, points.points.last())
        val screen = eventually { (model.state.value.screen as? Screen.Conversation)?.takeIf { it.key == FORK_KEY } }

        assertEquals("Fix the parser (fork)", screen.title)
        assertEquals("Second question", model.draft(FORK_KEY))
        assertNull(model.state.value.forkPoints)
        val fork = asked.last()
        assertEquals("a2", fork.point)
        assertTrue("a fork names an operation", !fork.operationId.isNullOrBlank())
        assertEquals(listOf(MobileSessionForkRequest(ROW.key), fork), asked.toList())
    }

    @Test fun `a fork whose answer never came is retried as the same operation`() {
        unconfirmedOnce = true
        model.startFork(ROW)
        val points = eventually { model.state.value.forkPoints }
        model.forkAt(points, points.points.last())
        eventually { model.state.value.snack?.message?.takeIf { it == MobileRefusal.FORK_UNCONFIRMED.message } }

        model.startFork(ROW)
        model.forkAt(eventually { model.state.value.forkPoints }, points.points.last())
        eventually { (model.state.value.screen as? Screen.Conversation)?.takeIf { it.key == FORK_KEY } }

        val forks = asked.filter { !it.listsPoints }
        assertEquals(2, forks.size)
        assertEquals("the machine can answer the retry with the fork it already made", forks[0].operationId, forks[1].operationId)
    }

    @Test fun `a draft already typed in the fork is not replaced`() {
        model.setDraft(FORK_KEY, "my own words")
        model.startFork(ROW)
        val points = eventually { model.state.value.forkPoints }

        model.forkAt(points, points.points.last())
        eventually { (model.state.value.screen as? Screen.Conversation)?.takeIf { it.key == FORK_KEY } }

        assertEquals("my own words", model.draft(FORK_KEY))
    }

    @Test fun `a fork the machine has not listed yet leaves the reader on the list, told so`() {
        listed = false
        model.startFork(ROW)
        val points = eventually { model.state.value.forkPoints }

        model.forkAt(points, points.points.first())
        eventually { model.state.value.snack?.message?.takeIf { it.startsWith("The fork was saved") } }

        assertEquals(Screen.Fleet, model.state.value.screen)
    }

    @Test fun `a Codex chat forks whole at once, with no picker`() {
        model.startFork(CODEX)
        eventually { (model.state.value.screen as? Screen.Conversation)?.takeIf { it.key == FORK_KEY } }

        assertNull(model.state.value.forkPoints)
        assertEquals(1, asked.size)
        assertTrue(asked.single().whole)
        assertNull(asked.single().point)
        assertEquals("a whole fork carries no prompt back", "", model.draft(FORK_KEY))
    }

    @Test fun `a chat the machine will not fork now says why and opens no picker`() {
        refused = "Wait for this turn to finish before forking the conversation."
        model.startFork(ROW)
        eventually { model.state.value.snack?.message?.takeIf { it == refused } }

        assertNull(model.state.value.forkPoints)
        assertEquals(listOf(MobileSessionForkRequest(ROW.key)), asked.toList())
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
        override fun fleet() = MobileFleetSnapshot(listOf(ROW, CODEX), 0, emptyList(), null, 1)
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
                if (url.path != MobileSessionForkRequest.ROUTE) return 404
                val request = MobileSessionForkRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                asked += request
                if (!request.listsPoints && unconfirmedOnce) {
                    unconfirmedOnce = false
                    answer = MobileRefusal.FORK_UNCONFIRMED.toJson().toString()
                    return MobileRefusal.FORK_UNCONFIRMED.status
                }
                answer = when {
                    request.listsPoints -> MobileSessionForkPoints(
                        request.key,
                        points = listOf(MobileForkPoint("a1", "First question", 1), MobileForkPoint("a2", "Second question", 2)),
                        refused = refused,
                    ).toJson()
                    listed -> MobileSessionForkResult(
                        request.key, true, "Forked as \u201cFix the parser (fork)\u201d.", FORK_KEY, "Fix the parser (fork)", listed = true,
                        promptText = if (request.whole) null else "Second question",
                    ).toJson()
                    else -> MobileSessionForkResult(
                        request.key, true, "The fork was saved but hasn't appeared in the chat list yet.", FORK_KEY, "", listed = false,
                    ).toJson()
                }.toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val FORK_KEY = "claude:/repo:fork"
        val ROW = MobileFleetRow(
            key = "claude:/repo:session", vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
            projectName = "repo", gitBranch = null, title = "Fix the parser", attention = null, waitingReason = null,
            lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 4,
        )
        val CODEX = ROW.copy(key = "codex:/repo:thread", vendor = AgentVendor.CODEX, title = "Codex task")
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("a.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
