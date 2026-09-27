package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileDeskCommands
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * Codex's `/ps` through the view model and a real [BridgeClient]: the machine's lines about the
 * terminals Codex recorded stay on screen over the open chat, "none recorded" is answered in the
 * desk's words, and a refusal is a notice, not a silent nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexPsFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var psReply: Pair<Int, String> = 200 to ANSWER
    private val psReads = mutableListOf<String>()
    private var transcriptReads = 0
    private var model: DeckViewModel? = null

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

    @Test fun `the machine's lines about the terminals are kept on screen over the chat that asked`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.CODEX_PS)
        settle { model.state.value.notice != null }

        assertEquals(listOf(TARGET.key), psReads)
        assertEquals(LINES, model.state.value.notice)
    }

    @Test fun `a chat with no recorded terminals is answered in the machine's words`() {
        psReply = 200 to """{"v":1,"key":"${TARGET.key}","text":"Codex started no background terminals in this chat"}"""
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.CODEX_PS)
        settle { model.state.value.notice != null }

        assertEquals("Codex started no background terminals in this chat", model.state.value.notice)
    }

    @Test fun `a refused read is a notice, not a silent nothing`() {
        psReply = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.CODEX_PS)
        settle { model.state.value.notice != null }

        assertEquals(listOf(TARGET.key), psReads)
    }

    // ---- harness (as ScheduleDuplicateFlowTest) --------------------------------------------

    private fun opened(): DeckViewModel {
        val opened = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }
        model = opened
        opened.open(DeepLink.Conversation(TARGET.key, TARGET.title, TARGET.vendor, TARGET.projectPath))
        settle { transcriptReads > 0 && opened.state.value.hello != null }
        return opened
    }

    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            ShadowLooper.idleMainLooper()
            if (until()) return
        }
        throw AssertionError("never settled: reads=$psReads notice=${model?.state?.value?.notice} snack=${model?.state?.value?.snack}")
    }

    private fun fakeBridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            private fun reply(): Pair<Int, String> = when {
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":["fleet","transcript","codex-ps"]}"""
                url.path == "/v1/codex-ps" -> psReply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/codex-ps") psReads += java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
                return reply().first
            }
            override fun getErrorStream(): InputStream = reply().second.byteInputStream()
            override fun getInputStream(): InputStream = reply().second.byteInputStream()
        }
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
        val TARGET = Screen.Conversation(
            key = "codex:/repo:thread", title = "Migrate",
            vendor = AgentVendor.CODEX, projectPath = "/repo",
        )
        val LINES = "Background terminals Codex started in this chat:\nnpm run dev · started 10:04 · in /repo/web"
        val ANSWER = """{"v":1,"key":"${TARGET.key}","text":"${LINES.replace("\n", "\\n")}"}"""
    }
}
