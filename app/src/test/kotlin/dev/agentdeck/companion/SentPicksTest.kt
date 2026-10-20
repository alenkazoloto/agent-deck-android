package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.ComposerPicks
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * t3code #10202/#10684/#11031: a model or effort picked on the phone outlived the send that used
 * it, so it kept overruling the desk's own selector after the chat's model was switched there.
 *
 * Through the real view model and a real [BridgeClient]: once a send lands on a machine that
 * writes the picks onto the chat's selectors, the composer follows the desk again. A machine that
 * does not would leave the desk's old model to run the next turn, so the picks stay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SentPicksTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore

    /** The model each send this fake machine accepted named, in order. */
    private val sentModels = mutableListOf<String?>()

    private var capabilities = emptyList<String>()

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

    @Test fun `picks a machine took are dropped once their send lands`() {
        capabilities = ALL + MobileProtocol.Capability.SEND_SELECTORS
        val model = model()

        model.setComposerPick(KEY, ComposerPicks.Field.MODEL, "sonnet")
        model.setComposerPick(KEY, ComposerPicks.Field.EFFORT, "max")
        model.send(TARGET, "continue", stopFirst = false)
        settle(model)

        assertEquals("the pick was not what the machine ran", listOf<String?>("sonnet"), sentModels)
        assertNull("a pick outlived the send that carried it", model.state.value.composerPicks[KEY])
    }

    @Test fun `an older machine keeps the pick, which nothing else carries`() {
        capabilities = ALL
        val model = model()

        model.setComposerPick(KEY, ComposerPicks.Field.MODEL, "sonnet")
        model.send(TARGET, "continue", stopFirst = false)
        settle(model)

        assertEquals(listOf<String?>("sonnet"), sentModels)
        assertEquals(
            "a machine that never wrote the pick lost it",
            "sonnet",
            model.state.value.composerPicks[KEY]?.picked?.get(ComposerPicks.Field.MODEL),
        )
    }

    @Test fun `a pick re-made while the send was on the wire keeps its new answer`() {
        val picks = ComposerPicks()
            .with(ComposerPicks.Field.MODEL, "opus")
            .with(ComposerPicks.Field.EFFORT, "low")

        val left = picks.without(MobileRunSelection(model = "sonnet", effort = "low", permissionMode = null))

        assertEquals(mapOf<ComposerPicks.Field, String?>(ComposerPicks.Field.MODEL to "opus"), left.picked)
    }

    /** The machine writes only named values, so a picked Default is held by the app or by nothing. */
    @Test fun `a picked Default outlives its send`() {
        val picks = ComposerPicks().with(ComposerPicks.Field.MODEL, null).with(ComposerPicks.Field.EFFORT, "low")

        val left = picks.without(MobileRunSelection(model = null, effort = "low", permissionMode = null))

        assertEquals(mapOf<ComposerPicks.Field, String?>(ComposerPicks.Field.MODEL to null), left.picked)
    }

    // ---- harness -------------------------------------------------------------------------

    private fun model(): DeckViewModel {
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }
        model.refreshHello()
        awaitUntil { model.state.value.hello != null }
        return model
    }

    private fun settle(model: DeckViewModel) = awaitUntil {
        val state = model.state.value
        state.delivering == null && state.outgoing.isEmpty
    }

    private fun awaitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (done()) return
        }
        throw AssertionError("the view model never settled")
    }

    private fun hello() = MobileHello(
        protocolVersion = MobileProtocol.VERSION, machineName = "desk", ideName = "IDE", pluginVersion = "1",
        capabilities = capabilities,
        effort = mapOf(AgentVendor.CLAUDE to listOf(MobileModelOption("low", "Low"), MobileModelOption("max", "Max"))),
    )

    private fun fakeBridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = body
            override fun getResponseCode() = 200
            override fun getErrorStream(): InputStream = "{}".byteInputStream()
            override fun getInputStream(): InputStream = when {
                url.path.endsWith("/v1/hello") -> hello().toJson().toString().byteInputStream()
                url.path.endsWith("/v1/send") -> {
                    sentModels += MobileProtocol.parseObject(body.toString(Charsets.UTF_8))
                        ?.get("model")?.takeUnless { it.isJsonNull }?.asString
                    """{"v":1,"taskId":"task-${sentModels.size}","state":"queued"}""".byteInputStream()
                }
                else -> "{}".byteInputStream()
            }
        }
    }

    private companion object {
        const val KEY = "claude:/repo:session"
        val ALL = listOf(
            MobileProtocol.Capability.MODELS, MobileProtocol.Capability.EFFORT, MobileProtocol.Capability.PERMISSION_MODES,
        )
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
        val TARGET = Screen.Conversation(
            key = KEY, title = "Fix the parser", vendor = AgentVendor.CLAUDE, projectPath = "/repo",
        )
    }
}
