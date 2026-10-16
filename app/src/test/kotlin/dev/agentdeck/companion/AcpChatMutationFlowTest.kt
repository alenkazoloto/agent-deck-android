package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileAcpChat
import dev.agentdeck.companion.data.ReadingAnchor
import com.github.claudeagents.core.mobile.MobileAcpCall
import com.github.claudeagents.core.mobile.MobileAcpOutcome
import com.github.claudeagents.core.mobile.MobileAcpVendorDecisionRequest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AcpChatMutationFlowTest {
    private lateinit var app: Application
    private lateinit var store: SecureStore
    private val posted = mutableListOf<Pair<String, JsonObject>>()
    @Volatile private var reads = 0
    private var loseReply = false
    @Volatile private var page = page("Original passage", pending = true)

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

    @Test fun `lost vendor decision response refreshes without retrying or consuming the draft`() = runBlocking {
        val model = open()
        model.setDraft(KEY, "A draft to keep")
        val before = reads
        loseReply = true
        val failure = model.decideAcp(KEY, "epoch-original", MobileAcpCall("call", "captured-token", "plan"), MobileAcpOutcome("accepted"))
        assertNotNull(failure)
        settle { reads > before }
        assertEquals(1, posted.size)
        val decision = MobileAcpVendorDecisionRequest.fromJson(posted.single().second)!!
        assertEquals("epoch-original", decision.epoch)
        assertEquals("captured-token", decision.decisionToken)
        assertEquals("A draft to keep", model.draft(KEY))
        assertTrue(model.state.value.outgoing.items.isEmpty())
    }

    @Test fun `config and reopen use separate controls and preserve row draft`() = runBlocking {
        val model = open()
        model.setDraft(KEY, "Keep the identity")
        model.configureAcp(KEY, "epoch", "model-id", "offered-id")
        model.reopenAcp(KEY)
        assertEquals(listOf("/v1/acp/config", "/v1/acp/reopen"), posted.map { it.first })
        assertEquals("offered-id", posted.first().second.get("value").asString)
        assertEquals(KEY, posted.last().second.get("key").asString)
        assertEquals("Keep the identity", model.draft(KEY))
    }

    @Test fun `desktop answer clears phone decision while older transcript passage stays in place`() {
        val model = open()
        model.rememberReading(MACHINE.id, KEY, ReadingAnchor("t0", 0, 0, followingLatest = false))
        assertEquals(1, model.state.value.transcript?.acp?.pendingCalls?.size)
        page = page("Changed passage", pending = false)
        model.loadTranscript(KEY, quiet = true)
        settle { model.state.value.pendingTranscript != null }
        assertEquals("Original passage", model.state.value.transcript?.turns?.single()?.text)
        assertTrue(model.state.value.transcript?.acp?.pendingCalls?.isEmpty() == true)
    }

    private fun page(text: String, pending: Boolean): String = JsonObject().apply {
        addProperty("v", 1)
        addProperty("key", KEY)
        add("turns", JsonParser.parseString("""[{"id":"t0","role":"assistant","text":"$text","timestampMs":1}]"""))
        add("acp", MobileAcpChat("epoch", pendingCalls = if (pending) listOf(MobileAcpCall("call", "token", "plan", plan = "Plan")) else emptyList()).toJson())
    }.toString()

    private fun open(): DeckViewModel {
        val model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
        model.open(DeepLink.Conversation(KEY, "Cursor chat", AgentVendor.CLAUDE, "/work"))
        settle { reads > 0 && model.state.value.hello != null && model.state.value.transcript?.key == KEY }
        return model
    }
    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            ShadowLooper.idleMainLooper()
            if (until()) return
        }
        throw AssertionError("ACP mutation never refreshed")
    }
    private fun bridge() = BridgeClient(listOf("lan.test", "remote.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val output = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = output
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) reads++
                if (url.path.startsWith("/v1/acp/")) {
                    posted += url.path to JsonParser.parseString(output.toString(Charsets.UTF_8)).asJsonObject
                    if (loseReply) throw IOException("reply lost")
                }
                return 200
            }
            override fun getInputStream(): InputStream = when {
                url.path == "/v1/hello" -> """{"v":1,"machine":"desk","capabilities":["fleet","transcript","acp-vendor-decisions","acp-config"]}"""
                url.path.startsWith("/v1/session/") -> page
                else -> "{}"
            }.byteInputStream()
        }
    }
    private companion object {
        const val KEY = "acp:cursor/root"
        val MACHINE = PairedMachine(machineName = "desk", hosts = listOf("lan.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-acp")
    }
}
