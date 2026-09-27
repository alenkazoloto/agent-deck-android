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
import org.junit.Assert.assertTrue
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
 * The desk's `/hooks` through the view model and a real [BridgeClient]: the command opens
 * the sheet over the chat, the sheet's read is the machine's hook list for that chat's key, and a refusal is
 * a null the sheet words itself rather than a silent nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HooksFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var hooksReply: Pair<Int, String> = 200 to ANSWER
    private val hooksReads = ArrayList<String>()
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

    @Test fun `the command opens the hooks sheet over the chat that asked`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.HOOKS)

        assertEquals(MobileDeskCommands.Action.HOOKS, model.deskSheet.value)
        assertEquals("the sheet reads on its own; the command itself asks nothing", emptyList<String>(), hooksReads)
    }

    @Test fun `the sheet's read is the machine's hook list for the open chat`() {
        val model = opened()

        val read = runBlocking { model.loadHooks() }

        assertEquals(listOf("PreToolUse", "SessionStart"), read?.rows?.map { it.event })
        assertEquals("guard.sh", read?.rows?.first()?.program)
        assertEquals(30, read?.rows?.first()?.timeoutSeconds)
        assertEquals(false, read?.rows?.last()?.runs)
        assertEquals(1, hooksReads.size)
        assertEquals("key=" + java.net.URLEncoder.encode(TARGET.key, "UTF-8"), hooksReads.single())
    }

    @Test fun `a refused read is null, not empty rows`() {
        hooksReply = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()

        assertNull(runBlocking { model.loadHooks() })
        assertEquals(1, hooksReads.size)
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
        throw AssertionError("never settled: reads=$hooksReads snack=${model?.state?.value?.snack}")
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
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":["fleet","transcript","hooks"]}"""
                url.path == "/v1/hooks" -> hooksReply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/hooks") hooksReads += url.query.orEmpty()
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
        val ANSWER = """{"v":1,"key":"claude:/repo:session","rows":[{"event":"PreToolUse","matcher":"Bash","origin":"Project","program":"guard.sh","timeout":30},{"event":"SessionStart","matcher":"","origin":"User","program":"notify","runs":false}]}"""
    }
}
