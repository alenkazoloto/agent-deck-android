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
 * The desk's `/rule [topic]` through the view model and a real [BridgeClient]: the topic reaches the machine as
 * typed, the prompt the machine composes is sent into that chat as an ordinary message — not the two-word line —
 * and a prompt that could not be composed comes back to an empty composer with nothing sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var reply: Pair<Int, String> = 200 to ANSWER
    private val asked = mutableListOf<Pair<String, String>>()
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

    @Test fun `a typed topic posts as typed and the machine's prompt is sent into the chat`() {
        val model = opened()

        model.rule(TARGET, " tests use fakes ")
        settle { sends.isNotEmpty() }

        assertEquals(listOf(TARGET.key to "tests use fakes"), asked)
        assertTrue("the machine's words travel as the message: ${sends.single()}", PROMPT in sends.single() && TARGET.key in sends.single())
        assertEquals("nothing is left in the composer", "", model.draft(TARGET.key))
    }

    @Test fun `a bare command asks with no topic and still sends the prompt`() {
        val model = opened()

        model.deskNavigation(MobileDeskCommands.Action.RULE)
        settle { sends.isNotEmpty() }

        assertEquals(listOf(TARGET.key to ""), asked)
        assertEquals("", model.draft(TARGET.key))
    }

    @Test fun `a prompt the machine did not compose is given back with its reason and nothing is sent`() {
        reply = 200 to """{"v":1,"key":"${TARGET.key}","promptText":"","text":"Nothing to write.","done":false}"""
        val model = opened()

        model.rule(TARGET, "tests use fakes")
        settle { model.state.value.notice != null }

        assertEquals("Nothing to write.", model.state.value.notice)
        assertEquals("/rule tests use fakes", model.draft(TARGET.key))
        assertTrue(sends.isEmpty())
    }

    @Test fun `a refused request is a notice and gives the line back, without overwriting a new draft`() {
        reply = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()

        model.rule(TARGET, "")
        settle { model.state.value.notice != null }
        assertEquals("/rule", model.draft(TARGET.key))

        model.setDraft(TARGET.key, "something newer")
        model.rule(TARGET, "")
        settle { asked.size == 2 }
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        assertEquals("something newer", model.draft(TARGET.key))
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
        throw AssertionError("never settled: asked=$asked notice=${model?.state?.value?.notice} snack=${model?.state?.value?.snack}")
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
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":["fleet","transcript","rule-prompt"]}"""
                url.path == "/v1/rule-prompt" -> reply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            private val posted = java.io.ByteArrayOutputStream()
            override fun getOutputStream(): java.io.OutputStream = posted
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/rule-prompt") {
                    val body = com.google.gson.JsonParser.parseString(posted.toString(Charsets.UTF_8)).asJsonObject
                    asked += body.get("key").asString to body.get("topic").asString
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
        const val PROMPT = "Turn what this conversation established into a rule"
        val ANSWER = """{"v":1,"key":"${TARGET.key}","promptText":"$PROMPT for Claude Code.","text":"","done":true}"""
    }
}
