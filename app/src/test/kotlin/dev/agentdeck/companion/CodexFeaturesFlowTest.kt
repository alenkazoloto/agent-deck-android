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
import kotlinx.coroutines.runBlocking
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
 * The desk's Codex `/experimental` through the view model and a real [BridgeClient]: the command opens the sheet
 * over the chat, the sheet's read is the machine's feature list, and a refusal is a null the sheet words itself
 * rather than a silent nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexFeaturesFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var featuresReply: Pair<Int, String> = 200 to ANSWER
    private var featuresReads = 0
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

    @Test fun `the command opens the features sheet over the chat that asked`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.CODEX_FEATURES)

        assertEquals(MobileDeskCommands.Action.CODEX_FEATURES, model.deskSheet.value)
        assertEquals("the sheet reads on its own; the command itself asks nothing", 0, featuresReads)
    }

    @Test fun `the sheet's read is the machine's feature list`() {
        val model = opened()

        val features = runBlocking { model.loadCodexFeatures() }

        assertEquals(1, featuresReads)
        assertEquals(listOf("network_proxy", "shell_tool"), features?.features?.map { it.name })
        assertEquals("On · default off", features?.features?.first()?.state)
        assertEquals("Network proxy", features?.features?.first()?.title)
        assertNull(features?.notice)
    }

    @Test fun `a refused read is null, not an empty list`() {
        featuresReply = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()

        assertNull(runBlocking { model.loadCodexFeatures() })
        assertEquals(1, featuresReads)
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
        throw AssertionError("never settled: reads=$featuresReads snack=${model?.state?.value?.snack}")
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
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":["fleet","transcript","codex-features"]}"""
                url.path == "/v1/codex-features" -> featuresReply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/codex-features") featuresReads++
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
            key = "claude:/repo:session", title = "Migrate",
            vendor = AgentVendor.CLAUDE, projectPath = "/repo",
        )
        val ANSWER = """{"v":1,"summary":"2 features — 1 set away from its default.","features":[{"title":"Network proxy","name":"network_proxy","stage":"Beta","state":"On · default off"},{"title":"shell_tool","name":"shell_tool","stage":"Stable","state":"On"}]}"""
    }
}
