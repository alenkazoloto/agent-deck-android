package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileDeskCommands
import com.github.claudeagents.core.mobile.MobileRecapQuery
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
 * The desk's `/recap` through the view model and a real [BridgeClient]: the machine's line is
 * raised over the open chat whatever the away window or the reader's switch say, a chat with no
 * send to quote is answered in the desk's words, and a refusal is a notice, not a silent nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecapNowFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var recapReply: Pair<Int, String> = 200 to ANSWER
    private val recapReads = mutableListOf<String>()
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

    @Test fun `the machine's line is raised over the chat that asked, with no away window`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RECAP)
        settle { model.state.value.recap != null }

        assertEquals(listOf(TARGET.key), recapReads)
        val offer = model.state.value.recap!!
        assertEquals(TARGET.key, offer.key)
        assertEquals("You asked: \"Migrate the auth module\".", offer.recap.text)
        assertEquals(1_800_000_000_000, offer.recap.lastActiveMs)
    }

    @Test fun `a chat with nothing to recap is answered in the desk's words and raises no strip`() {
        recapReply = 200 to """{"v":1,"key":"${TARGET.key}"}"""
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RECAP)
        settle { model.state.value.snack != null }

        assertEquals(MobileRecapQuery.NOTHING_TO_RECAP, model.state.value.snack?.message)
        assertNull(model.state.value.recap)
    }

    @Test fun `a refused read is a notice and leaves no strip`() {
        recapReply = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RECAP)
        settle { model.state.value.notice != null }

        assertNull(model.state.value.recap)
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
        throw AssertionError("never settled: reads=$recapReads notice=${model?.state?.value?.notice} snack=${model?.state?.value?.snack}")
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
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":["fleet","transcript","recap"]}"""
                url.path == "/v1/recap" -> recapReply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/recap") recapReads += java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
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
        val ANSWER = """{"v":1,"key":"${TARGET.key}","recap":{"text":"You asked: \"Migrate the auth module\".","lastActiveMs":1800000000000,"userMessages":1}}"""
    }
}
