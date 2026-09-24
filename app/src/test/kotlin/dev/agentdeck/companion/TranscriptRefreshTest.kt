package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.ReadingAnchor
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * M8's pull, as a *composition*: what the gesture asks for, and what reaches the screen.
 *
 * The continuity merge parks a page that differs from the one on screen behind the
 * jump-to-latest whenever the reader is not following the tail — which is right for a reload
 * nobody asked for, and wrong for the pull, because the pull is only reachable *from* that
 * state (`PullToRefreshBox` fires at list index 0, which is where following is already off).
 * The first version wired the gesture straight to `loadTranscript`, so the spinner ran and the
 * screen did not move: indistinguishable from a dead link. The negative control below is the
 * same page through the same view model on the unpulled path, which must still park.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptRefreshTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore

    /** What the fake machine answers `/v1/session/{key}` with, changed between reads. */
    private var served = page("the first answer")

    /** Every transcript read the app actually made. */
    private var reads = 0

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
    }

    @After fun tearDown() {
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a pulled reload takes the machine's page instead of parking it`() = runBlocking {
        val model = opened()

        served = page("the first answer", "and the newest one")
        model.refreshTranscript(TARGET.key)
        settle(model)

        val state = model.state.value
        assertNull("the pull parked the page it asked for", state.pendingTranscript)
        assertEquals(
            listOf("the first answer", "and the newest one"),
            state.transcript?.turns?.map { it.text },
        )
        assertFalse("the spinner outlived its request", state.transcriptRefreshing)
    }

    /**
     * The control. Same view model, same page, same reader position — only nobody asked, so the
     * reader is not yanked and the jump-to-latest is what offers it. A pull wired to this path
     * is the defect; a pull that made *this* path take the page would be a worse one.
     */
    @Test fun `an unasked reload still parks the page behind the jump to latest`() = runBlocking {
        val model = opened()

        served = page("the first answer", "and the newest one")
        model.loadTranscript(TARGET.key)
        settle(model)

        val state = model.state.value
        assertNotNull("the reader was yanked to a page they did not ask for", state.pendingTranscript)
        assertEquals(listOf("the first answer"), state.transcript?.turns?.map { it.text })
    }

    /** A pull with no link never reaches the machine, so it may not leave a spinner behind. */
    @Test fun `a pull that cannot start leaves no spinner`() = runBlocking {
        val model = opened()
        reads = 0

        model.refreshTranscript("some:other:conversation")
        settle(model)

        assertEquals("a pull reached the machine for a conversation nobody is on", 0, reads)
        assertFalse(model.state.value.transcriptRefreshing)
    }

    // ---- harness ---------------------------------------------------------------------------

    /**
     * A conversation open and read back through — with the reader scrolled away from the tail,
     * which is both the state the pull is reachable from and the state the merge parks in.
     */
    private fun opened(): DeckViewModel {
        val model = model()
        model.openConversation(row())
        settle(model)
        model.rememberReading(MACHINE.id, TARGET.key, ReadingAnchor("t0", 0, 0, followingLatest = false))
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

    private fun model() = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }

    private fun row() = com.github.claudeagents.core.mobile.MobileFleetRow(
        key = TARGET.key, vendor = AgentVendor.CLAUDE, accountId = "default",
        projectPath = TARGET.projectPath, projectName = "repo", gitBranch = null,
        title = TARGET.title, attention = null, waitingReason = null, lastActivityMs = 1,
        costUsd = 0.0, costKnown = false, contextPct = null, messageCount = 1,
    )

    private fun page(vararg texts: String): String {
        val turns = texts.mapIndexed { index, text ->
            """{"id":"t$index","role":"assistant","text":"$text","timestampMs":1}"""
        }.joinToString(",")
        return """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[$turns],""" +
            """"hasMore":false,"costUsd":0.0,"costKnown":false,"running":false,"generatedAtMs":1}"""
    }

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
            override fun getResponseCode() = 200
            override fun getErrorStream(): InputStream = "{}".byteInputStream()
            override fun getInputStream(): InputStream {
                if (!url.path.startsWith("/v1/session/")) return "{}".byteInputStream()
                reads++
                return served.byteInputStream()
            }
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
