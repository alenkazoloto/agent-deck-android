package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionHandoffRequest
import com.github.claudeagents.core.mobile.MobileSessionHandoffResult
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
 * "Continue on another account" through the view model: the machine copies the chat onto the account
 * picked and the copy opens with the desk's continuation prompt waiting in its composer — a draft,
 * never a send. One the machine has not listed yet leaves the reader where they were, told so; a
 * refusal is the machine's own sentence; a retry after a dropped answer is the same operation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionHandoffFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    private val asked = CopyOnWriteArrayList<MobileSessionHandoffRequest>()
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

    @Test fun `the copy opens on the target account with the continuation prompt as a draft, not a send`() {
        model.continueOnAccount(ROW, "work", "Work")
        val screen = eventually { (model.state.value.screen as? Screen.Conversation)?.takeIf { it.key == COPY_KEY } }

        assertEquals("Fix the parser", screen.title)
        assertEquals(MobileSessionHandoffResult.CONTINUE_PROMPT, model.draft(COPY_KEY))
        val request = asked.single()
        assertEquals(MobileSessionHandoffRequest(ROW.key, "work", request.operationId), request)
        assertTrue("a handoff names an operation", !request.operationId.isNullOrBlank())
    }

    @Test fun `a handoff whose answer never came is retried as the same operation`() {
        unconfirmedOnce = true
        model.continueOnAccount(ROW, "work", "Work")
        eventually { model.state.value.snack?.message?.takeIf { it == MobileRefusal.HANDOFF_UNCONFIRMED.message } }

        model.continueOnAccount(ROW, "work", "Work")
        eventually { (model.state.value.screen as? Screen.Conversation)?.takeIf { it.key == COPY_KEY } }

        assertEquals(2, asked.size)
        assertEquals("the machine can answer the retry with the copy it already made", asked[0].operationId, asked[1].operationId)
    }

    @Test fun `a draft already typed in the copy is not replaced`() {
        model.setDraft(COPY_KEY, "my own words")

        model.continueOnAccount(ROW, "work", "Work")
        eventually { (model.state.value.screen as? Screen.Conversation)?.takeIf { it.key == COPY_KEY } }

        assertEquals("my own words", model.draft(COPY_KEY))
    }

    @Test fun `a copy the machine has not listed yet leaves the reader on the list, told so`() {
        listed = false

        model.continueOnAccount(ROW, "work", "Work")
        eventually { model.state.value.snack?.message?.takeIf { it.startsWith("The copy was saved") } }

        assertEquals(Screen.Fleet, model.state.value.screen)
    }

    @Test fun `a chat the machine will not copy says why and opens nothing`() {
        refused = "This chat is running. Wait for it to finish, then continue it on another account."

        model.continueOnAccount(ROW, "work", "Work")
        eventually { model.state.value.snack?.message?.takeIf { it == refused } }

        assertEquals(Screen.Fleet, model.state.value.screen)
        assertEquals("", model.draft(COPY_KEY))
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
        override fun fleet() = MobileFleetSnapshot(listOf(ROW), 0, emptyList(), null, 1)
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
                if (url.path != MobileSessionHandoffRequest.ROUTE) return 404
                val request = MobileSessionHandoffRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                asked += request
                if (unconfirmedOnce) {
                    unconfirmedOnce = false
                    answer = MobileRefusal.HANDOFF_UNCONFIRMED.toJson().toString()
                    return MobileRefusal.HANDOFF_UNCONFIRMED.status
                }
                answer = when {
                    refused != null -> MobileSessionHandoffResult(request.key, false, refused.orEmpty()).toJson()
                    listed -> MobileSessionHandoffResult(
                        request.key, true, "Copied to “Work”. Send to continue there.", COPY_KEY, "Fix the parser", listed = true,
                        promptText = MobileSessionHandoffResult.CONTINUE_PROMPT,
                    ).toJson()
                    else -> MobileSessionHandoffResult(
                        request.key, true, "The copy was saved but hasn't appeared in the chat list yet.", COPY_KEY, "Fix the parser", listed = false,
                        promptText = MobileSessionHandoffResult.CONTINUE_PROMPT,
                    ).toJson()
                }.toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val COPY_KEY = "claude:work:/repo:session"
        val ROW = MobileFleetRow(
            key = "claude:default:/repo:session", vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
            projectName = "repo", gitBranch = null, title = "Fix the parser", attention = null, waitingReason = null,
            lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 4,
        )
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("a.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
