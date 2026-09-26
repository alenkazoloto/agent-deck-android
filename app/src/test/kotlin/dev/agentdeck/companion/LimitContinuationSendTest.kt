package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.LimitContinuation
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The "Continue at …" offer's write, through the view model and a real [BridgeClient]: the
 * desk's sentence goes into *this* chat (its key, not a new chat) at the reset's due time, and
 * an edited prompt survives a refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LimitContinuationSendTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var sendReply: Pair<Int, String> = 200 to """{"v":1,"taskId":"t-1","state":"queued"}"""
    private val sendBodies = mutableListOf<String>()
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

    @Test fun `continue after reset queues the desk's sentence into this chat at the due time`() {
        val model = opened()
        val due = System.currentTimeMillis() + 3_600_000

        model.scheduleContinuation(TARGET, due)
        settle { model.state.value.snack != null }

        val body = MobileProtocol.parseObject(sendBodies.single())!!
        assertEquals(TARGET.key, body.get("key")?.asString)
        assertFalse(body.get("newChat")?.asBoolean ?: false)
        assertEquals(due, body.get("dueAtMs")?.asLong)
        assertEquals(LimitContinuation.PROMPT, body.get("prompt")?.asString)
        assertNull(model.state.value.drafts[LimitContinuation.draftKey(TARGET.key)])
    }

    @Test fun `a refused continuation keeps the edited prompt and says why`() {
        sendReply = 503 to """{"v":1,"error":"unavailable","message":"The IDE is busy."}"""
        val model = opened()
        val key = LimitContinuation.draftKey(TARGET.key)
        model.setDraft(key, "Finish the migration")

        model.scheduleContinuation(TARGET, System.currentTimeMillis() + 3_600_000)
        settle { model.state.value.notice != null }

        assertEquals("Finish the migration", model.state.value.drafts[key])
        assertEquals("Finish the migration", MobileProtocol.parseObject(sendBodies.single())!!.get("prompt")?.asString)
    }

    @Test fun `Usage's offer opens Schedule on that account, and closing the dialog forgets it`() {
        val model = opened()

        model.scheduleAfterReset(AgentVendor.CODEX, "codex-work")

        assertEquals(Screen.Scheduled, model.state.value.screen)
        assertEquals("codex-work", model.state.value.scheduleAfterReset?.accountId)
        assertEquals(AgentVendor.CODEX, model.state.value.scheduleAfterReset?.vendor)
        model.scheduleAfterResetDone()
        assertNull(model.state.value.scheduleAfterReset)
    }

    // ---- harness (as AnswerExpiryTest) ---------------------------------------------------

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
                url.path.endsWith("/v1/send") -> sendReply
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path.endsWith("/v1/send")) sendBodies += body.toString(Charsets.UTF_8)
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
    }
}
