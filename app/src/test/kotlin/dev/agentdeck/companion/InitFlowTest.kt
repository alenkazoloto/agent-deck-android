package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileDeskCommands
import com.github.claudeagents.core.mobile.MobileInitSetupRequest
import kotlinx.coroutines.runBlocking
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
 * The desk's `/init` through the view model and a real [BridgeClient]: the picker reads the machine's opening
 * state, Propose posts the picks for the open chat, the prompt the machine composes is sent into that chat as
 * an ordinary message, and a prompt not composed leaves the picks parked with the reason and sends nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InitFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var answer: Pair<Int, String> = 200 to ANSWER
    private val reads = mutableListOf<String>()
    private val posts = mutableListOf<com.google.gson.JsonObject>()
    private val sends = mutableListOf<String>()
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

    @Test fun `the picker's opening read is the machine's for the open chat`() {
        val model = opened()

        val setup = runBlocking { model.loadInitSetup() }

        assertEquals("CLAUDE.md", setup?.instructionsFile)
        assertEquals(listOf("AGENTS.md"), setup?.sources?.map { it.path })
        assertEquals(listOf("GET ${TARGET.key}"), reads)
    }

    @Test fun `Propose posts the picks for the open chat and sends the machine's prompt as a message`() {
        val model = opened()
        model.openInit("  we use Bazel ")
        assertEquals("we use Bazel", model.initNotes)
        assertEquals(MobileDeskCommands.Action.INIT, model.deskSheet.value)

        val failure = runBlocking { model.submitInit(MobileInitSetupRequest("ignored", skills = true, imports = listOf("AGENTS.md"), notes = "we use Bazel")) }
        settle { sends.isNotEmpty() }

        assertNull(failure)
        val posted = posts.single()
        assertEquals("the phone names the open chat, not the key the sheet carried", TARGET.key, posted.get("key").asString)
        assertTrue(posted.get("skills").asBoolean)
        assertEquals("AGENTS.md", posted.getAsJsonArray("imports").single().asString)
        assertTrue("the machine's words travel as the message: ${sends.single()}", PROMPT in sends.single() && TARGET.key in sends.single())
        assertNull("a sent prompt clears the parked picks", model.initDraft)
    }

    @Test fun `a bare command opens the picker with no notes`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.INIT)

        assertEquals(MobileDeskCommands.Action.INIT, model.deskSheet.value)
        assertEquals("", model.initNotes)
    }

    @Test fun `a prompt the machine did not compose is the reason, sends nothing and keeps the picks`() {
        answer = 200 to """{"v":1,"key":"${TARGET.key}","promptText":"","text":"Pick something to set up first.","done":false}"""
        val model = opened()
        val picks = MobileInitSetupRequest(TARGET.key, instructions = false, notes = "keep")
        model.setInitDraft(picks)

        val failure = runBlocking { model.submitInit(picks) }

        assertEquals("Pick something to set up first.", failure)
        assertEquals(picks, model.initDraft)
        assertTrue(sends.isEmpty())
    }

    @Test fun `a refused request is a reason, sends nothing and keeps the picks`() {
        answer = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()
        val picks = MobileInitSetupRequest(TARGET.key, skills = true)
        model.setInitDraft(picks)

        val failure = runBlocking { model.submitInit(picks) }

        assertTrue("the reader is told, not left on a silent sheet", !failure.isNullOrBlank())
        assertEquals(picks, model.initDraft)
        assertTrue(sends.isEmpty())
    }

    // ---- harness (as OutputStyleFlowTest) --------------------------------------------------

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
        throw AssertionError("never settled: posts=$posts notice=${model?.state?.value?.notice} snack=${model?.state?.value?.snack}")
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
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":["fleet","transcript","init-setup"]}"""
                url.path == "/v1/init-setup" && method == "POST" -> answer
                url.path == "/v1/init-setup" -> 200 to SETUP
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            private val posted = java.io.ByteArrayOutputStream()
            override fun getOutputStream(): java.io.OutputStream = posted
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/init-setup") {
                    if (method == "POST") posts += com.google.gson.JsonParser.parseString(posted.toString(Charsets.UTF_8)).asJsonObject
                    else reads += "GET " + url.query.removePrefix("key=").let { java.net.URLDecoder.decode(it, "UTF-8") }
                }
                if (url.path == "/v1/send") sends += posted.toString(Charsets.UTF_8)
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
        const val PROMPT = "Set up this repository for Claude Code"
        val ANSWER = """{"v":1,"key":"${TARGET.key}","promptText":"$PROMPT. Propose before you write.","text":"","done":true}"""
        val SETUP = """{"v":1,"key":"${TARGET.key}","instructionsFile":"CLAUDE.md","personalPath":"~/.claude/CLAUDE.md","hasInstructions":true,"sources":[{"path":"AGENTS.md","label":"AGENTS.md · AGENTS.md"}]}"""
    }
}
