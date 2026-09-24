package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * M2's expiry cases, through the view model and a real [BridgeClient]: what the reader is told
 * when the thing they acted on had gone by the time the machine read it — a question the agent
 * moved past or replaced, or a photo whose upload lapsed before Send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerExpiryTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore

    /** Status and body the fake machine answers `/v1/answer` and `/v1/send` with. */
    private var answerReply: Pair<Int, String> = 200 to """{"v":1,"parked":true}"""
    private var sendReply: Pair<Int, String> = 200 to """{"v":1,"taskId":"t-1","state":"queued"}"""
    private val answerBodies = mutableListOf<String>()
    private var transcriptReads = 0

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

    /** The card names its tool call, so the machine can bind the pick to the ask it was made on. */
    @Test fun `an answer names the question card it was picked on`() {
        val model = opened()

        model.answer(TARGET, "toolu_1", mapOf("Which port?" to "3011"))
        settle { answerBodies.isNotEmpty() }

        val body = MobileProtocol.parseObject(answerBodies.single())!!
        assertEquals("toolu_1", body.get("askId")?.asString)
        settle { model.state.value.snack != null }
        assertEquals("Answer sent", model.state.value.snack?.message)
    }

    /** Answered at the desk or the run ended: the pick ran as a new turn, and the reader is told so. */
    @Test fun `a question that had gone says the pick became a new message`() {
        answerReply = 200 to """{"v":1,"parked":false,"taskId":"t-2","state":"queued"}"""
        val model = opened()

        model.answer(TARGET, "toolu_1", mapOf("Which port?" to "3011"))
        settle { model.state.value.snack != null }

        assertEquals("That question had gone — sent as a new message", model.state.value.snack?.message)
    }

    /**
     * The agent asked something else since this card was drawn. The machine's sentence is shown
     * as is, and the page reloads so the newer card is the one on screen to answer.
     */
    @Test fun `a replaced question shows the refusal and reloads the newer card`() {
        answerReply = 409 to MobileRefusal.QUESTION_SUPERSEDED.toJson().toString()
        val model = opened()
        transcriptReads = 0

        model.answer(TARGET, "toolu_1", mapOf("Which port?" to "3011"))
        settle { model.state.value.notice != null && transcriptReads > 0 }

        assertEquals(MobileRefusal.QUESTION_SUPERSEDED.message, model.state.value.notice)
        assertEquals(1, model.state.value.answerFailures)
    }

    /** A photo that lapsed on the machine before Send: the text still ran, and the snack says which part did not. */
    @Test fun `an expired photo is reported after the text is sent`() {
        sendReply = 200 to """{"v":1,"taskId":"t-3","state":"queued","notice":"One photo had expired and was not sent with this message."}"""
        val model = opened()

        model.send(TARGET, "look at this", stopFirst = false)
        settle { model.state.value.snack != null && model.state.value.outgoing.isEmpty }

        assertEquals("One photo had expired and was not sent with this message.", model.state.value.snack?.message)
    }

    // ---- harness -------------------------------------------------------------------------

    private fun opened(): DeckViewModel {
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }
        model.open(DeepLink.Conversation(TARGET.key, TARGET.title, TARGET.vendor, TARGET.projectPath))
        settle { transcriptReads > 0 }
        return model
    }

    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            ShadowLooper.idleMainLooper()
            if (until()) return
        }
        throw AssertionError("never settled")
    }

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
            private fun reply(): Pair<Int, String> = when {
                url.path.endsWith("/v1/answer") -> answerReply
                url.path.endsWith("/v1/send") -> sendReply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path.endsWith("/v1/answer")) answerBodies += body.toString(Charsets.UTF_8)
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
            key = "claude:/repo:session", title = "Free the port",
            vendor = AgentVendor.CLAUDE, projectPath = "/repo",
        )
    }
}
