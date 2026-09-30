package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.data.ComposerPicks
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
 * The desk's `/savep <name>` through the view model and a real [BridgeClient]: the name reaches the machine as
 * typed with the picks the composer holds and the fields it leaves to the desk's selectors, the machine's line
 * stays on screen over the open chat, and a preset the machine did not save — a refused name, no grant, or a
 * failed request — comes back to an empty composer, as the desk keeps the line until the save lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetSaveFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var reply: Pair<Int, String> = 200 to ANSWER
    private var capabilities = """"fleet","transcript","preset-save""""
    private val asked = mutableListOf<com.google.gson.JsonObject>()
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

    @Test fun `a typed name posts as typed and keeps the machine's line on screen`() {
        val model = opened()

        model.savePreset(TARGET.key, " Night shift ")
        settle { model.state.value.notice != null }

        assertEquals(1, asked.size)
        assertEquals(TARGET.key, asked.single().get("key").asString)
        assertEquals("Night shift", asked.single().get("name").asString)
        assertEquals(SAVED, model.state.value.notice)
        assertEquals("a saved preset leaves the composer empty", "", model.draft(TARGET.key))
    }

    @Test fun `the picks the reader made travel, and a machine that reads the desk's selectors is told which fields it may read`() {
        capabilities += ""","send-desk-selection","effort""""
        val model = opened()
        model.setComposerPick(TARGET.key, ComposerPicks.Field.MODEL, "opus")

        model.savePreset(TARGET.key, "Night shift")
        settle { model.state.value.notice != null }

        val body = asked.single()
        assertEquals("opus", body.get("model").asString)
        val fields = body.getAsJsonArray("deskFields").map { it.asString }.toSet()
        assertEquals("only what was not picked is the desk's to read", setOf("effort", "permissionMode", "fastMode", "thinking", "ultracode"), fields)
    }

    @Test fun `a preset the machine did not save is given back with the machine's reason`() {
        reply = 200 to """{"v":1,"key":"${TARGET.key}","text":"$NOT_GRANTED","done":false}"""
        val model = opened()

        model.savePreset(TARGET.key, "Night shift")
        settle { model.state.value.notice != null }

        assertEquals(NOT_GRANTED, model.state.value.notice)
        assertEquals("/savep Night shift", model.draft(TARGET.key))
    }

    @Test fun `a refused request is a notice and gives the line back, without overwriting a new draft`() {
        reply = 404 to """{"v":1,"error":"not-found"}"""
        val model = opened()

        model.savePreset(TARGET.key, "Night shift")
        settle { model.state.value.notice != null }
        assertEquals("/savep Night shift", model.draft(TARGET.key))

        model.setDraft(TARGET.key, "something newer")
        model.savePreset(TARGET.key, "Night shift")
        settle { asked.size == 2 }
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        assertEquals("something newer", model.draft(TARGET.key))
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
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"machine":"desk","capabilities":[$capabilities]}"""
                url.path == "/v1/preset-save" -> reply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            private val posted = java.io.ByteArrayOutputStream()
            override fun getOutputStream(): java.io.OutputStream = posted
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path == "/v1/preset-save") {
                    asked += com.google.gson.JsonParser.parseString(posted.toString(Charsets.UTF_8)).asJsonObject
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
        const val SAVED = "— Saved preset Night shift —"
        val ANSWER = """{"v":1,"key":"${TARGET.key}","text":"$SAVED","done":true}"""
        const val NOT_GRANTED = "This machine does not let this phone save presets. Turn it on for this phone in the IDE, under Settings › Connections › Mobile › Devices, or save it there."
    }
}
