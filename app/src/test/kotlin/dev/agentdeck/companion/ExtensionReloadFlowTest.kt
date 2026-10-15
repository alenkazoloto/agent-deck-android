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
 * The desk's `/reload-plugins` and `/reload-skills` through the view model and a real [BridgeClient]: the
 * machine's report line stays on screen over the open chat, the scope reaches the machine as the desk's own
 * word, "not open" is the machine's reason, and a refusal is a notice, not a silent nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtensionReloadFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var reloadReply: Pair<Int, String> = 200 to ANSWER
    private val reloads = mutableListOf<Pair<String, String>>()
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

    @Test fun `a plugin reload posts its scope and keeps the machine's report on screen`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RELOAD_PLUGINS)
        settle { model.state.value.notice != null }

        assertEquals(listOf(TARGET.key to "plugins"), reloads)
        assertEquals(REPORT, model.state.value.notice)
    }

    @Test fun `a skills reload posts the skills scope`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RELOAD_SKILLS)
        settle { model.state.value.notice != null }

        assertEquals(listOf(TARGET.key to "skills"), reloads)
    }

    @Test fun `a chat whose project is not open says why`() {
        reloadReply = 200 to """{"v":1,"key":"${TARGET.key}","text":"$NOT_OPEN","done":false}"""
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RELOAD_PLUGINS)
        settle { model.state.value.notice != null }

        assertEquals(NOT_OPEN, model.state.value.notice)
    }

    @Test fun `a refused reload is a notice, not a silent nothing`() {
        reloadReply = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RELOAD_SKILLS)
        settle { model.state.value.notice != null }

        assertEquals(listOf(TARGET.key to "skills"), reloads)
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
        throw AssertionError("never settled: reloads=$reloads notice=${model?.state?.value?.notice} snack=${model?.state?.value?.snack}")
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
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":["fleet","transcript","extension-reload"]}"""
                url.path == "/v1/extension-reload" -> reloadReply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            private val posted = java.io.ByteArrayOutputStream()
            override fun getOutputStream(): java.io.OutputStream = posted
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/extension-reload") {
                    val body = com.google.gson.JsonParser.parseString(posted.toString(Charsets.UTF_8)).asJsonObject
                    reloads += body.get("key").asString to body.get("scope").asString
                }
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
        val REPORT = "Reloaded: 2 plugins · 9 skills · 1 agent · 3 hooks · 0 plugin MCP servers"
        val NOT_OPEN = "That chat's project is not open in the IDE. Open it there to reload its plugins and skills."
        val ANSWER = """{"v":1,"key":"${TARGET.key}","text":"$REPORT","done":true}"""
    }
}
