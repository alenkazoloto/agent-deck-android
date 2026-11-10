package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileMarketplaceChange
import com.github.claudeagents.core.mobile.MobileMcpAdd
import com.github.claudeagents.core.mobile.MobileMcpQuery
import com.github.claudeagents.core.mobile.MobileMcpRemove
import com.github.claudeagents.core.mobile.MobilePluginInstall
import com.github.claudeagents.core.mobile.MobilePluginToggle
import com.github.claudeagents.core.mobile.MobilePluginUninstall
import com.github.claudeagents.core.mobile.MobilePluginUpdate
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSkillCopy
import com.github.claudeagents.core.mobile.MobileSkillCreate
import com.github.claudeagents.core.mobile.MobileSkillState
import com.github.claudeagents.core.mobile.MobileSkillsQuery
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * Resources › MCP servers / Skills, agents and plugins / Marketplaces: a *definitive* machine refusal
 * (a withheld per-device grant such as [MobileRefusal.MCP_ADD_DISABLED], a revoked permission-decisions
 * scope, a stale plugin id) is the machine ANSWERING, and its own named sentence must reach the sheet —
 * never the generic "did not answer" wording these eleven [DeckViewModel] functions reserve for a
 * genuinely dropped/uncertain round trip. Each previously discarded [dev.agentdeck.companion.data.BridgeRefusal.message]
 * on every thrown exception, the same bug [DeckViewModel.actOnAcpAgent] (just above them in source) was
 * already written to avoid — see its comment: "A refusal is the machine answering: it started nothing,
 * and its own sentence says why (a grant taken away, a phone it does not know)."
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResourceGrantRefusalFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var targetPath = ""
    private var refusal: Pair<Int, String> = 200 to "{}"

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

    @Test fun `a withheld MCP add grant is the machine's own sentence, not the uncertain one`() {
        assertRefusal(MobileMcpQuery.ADD_ROUTE, MobileRefusal.MCP_ADD_DISABLED) {
            it.addMcpServer(MobileMcpAdd("claude", "docs", "https://docs.example.com"))
        }
    }

    @Test fun `a refused MCP remove is the machine's own sentence`() {
        assertRefusal(MobileMcpQuery.REMOVE_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.removeMcpServer(MobileMcpRemove("claude", "docs"))
        }
    }

    @Test fun `a refused plugin toggle is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.TOGGLE_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.togglePlugin(MobilePluginToggle("demo@market", true))
        }
    }

    @Test fun `a refused skill state change is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.STATE_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.setSkillState(MobileSkillState("/deploy", "on"))
        }
    }

    @Test fun `a refused skill copy is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.COPY_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.copySkill(MobileSkillCopy("/deploy", true))
        }
    }

    @Test fun `a refused marketplace refresh is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.MARKETPLACE_REFRESH_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.refreshMarketplace(MobileMarketplaceChange("acme"))
        }
    }

    @Test fun `a refused marketplace removal is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.MARKETPLACE_REMOVE_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.removeMarketplace(MobileMarketplaceChange("acme"))
        }
    }

    @Test fun `a refused skill creation is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.CREATE_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.createSkill(MobileSkillCreate("deploy", true))
        }
    }

    @Test fun `a refused plugin update is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.UPDATE_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.updatePlugin(MobilePluginUpdate("demo@market"))
        }
    }

    @Test fun `a refused plugin install is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.INSTALL_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.installPlugin(MobilePluginInstall("demo@market"))
        }
    }

    @Test fun `a refused plugin uninstall is the machine's own sentence`() {
        assertRefusal(MobileSkillsQuery.UNINSTALL_ROUTE, MobileRefusal.PERMISSIONS_DISABLED) {
            it.uninstallPlugin(MobilePluginUninstall("demo@market"))
        }
    }

    private fun assertRefusal(path: String, refusalCode: MobileRefusal, call: suspend (DeckViewModel) -> String?) {
        targetPath = path
        refusal = refusalCode.status to refusalCode.toJson().toString()
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }
        val shown = runBlocking { call(model) }
        assertEquals(refusalCode.message, shown)
    }

    private fun fakeBridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val posted = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = posted
            private fun reply(): Pair<Int, String> = when {
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":[]}"""
                url.path == targetPath -> refusal
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int = reply().first
            override fun getErrorStream(): InputStream = reply().second.byteInputStream()
            override fun getInputStream(): InputStream = reply().second.byteInputStream()
        }
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
