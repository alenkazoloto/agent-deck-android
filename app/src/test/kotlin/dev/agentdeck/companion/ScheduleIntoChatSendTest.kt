package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.ScheduleRepeat
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
 * mobile-todo Scheduling row 1, through the view model and a real [BridgeClient]: the composer's
 * draft goes into *this* chat (its key, not a new chat) at the picked time and cadence, is
 * consumed once the machine accepts, and survives a refusal where it was typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleIntoChatSendTest {

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

    @Test fun `the composer's text is scheduled into this chat and consumed once accepted`() {
        val model = opened()
        model.setDraft(TARGET.key, "Rerun the nightly suite")
        val due = System.currentTimeMillis() + 3_600_000

        model.scheduleIntoChat(TARGET, due, ScheduleRepeat(everyMs = 3_600_000))
        settle { model.state.value.snack != null }

        val body = MobileProtocol.parseObject(sendBodies.single())!!
        assertEquals(TARGET.key, body.get("key")?.asString)
        assertFalse(body.get("newChat")?.asBoolean ?: false)
        assertEquals(due, body.get("dueAtMs")?.asLong)
        assertEquals(3_600_000L, body.get("repeatEveryMs")?.asLong)
        assertEquals("Rerun the nightly suite", body.get("prompt")?.asString)
        assertEquals("", model.state.value.drafts[TARGET.key].orEmpty())
    }

    @Test fun `waiting for the run posts the flag, not a time, and says so once accepted`() {
        val model = opened()
        model.setDraft(TARGET.key, "Then rerun the suite")

        model.scheduleIntoChat(TARGET, System.currentTimeMillis(), repeat = null, afterRun = true)
        settle { model.state.value.snack != null }

        val body = MobileProtocol.parseObject(sendBodies.single())!!
        assertEquals(true, body.get("afterRun")?.asBoolean)
        assertEquals(TARGET.key, body.get("key")?.asString)
        assertEquals("Scheduled in this chat for when its run finishes", model.state.value.snack?.message)
        assertEquals("", model.state.value.drafts[TARGET.key].orEmpty())
    }

    @Test fun `an ordinary schedule leaves the flag off the wire`() {
        val model = opened()
        model.setDraft(TARGET.key, "Rerun the nightly suite")

        model.scheduleIntoChat(TARGET, System.currentTimeMillis() + 3_600_000, repeat = null)
        settle { model.state.value.snack != null }

        assertNull(MobileProtocol.parseObject(sendBodies.single())!!.get("afterRun"))
    }

    @Test fun `a refused schedule keeps the composer's text and says why`() {
        sendReply = 503 to """{"v":1,"error":"unavailable","message":"The IDE is busy."}"""
        val model = opened()
        model.setDraft(TARGET.key, "Rerun the nightly suite")

        model.scheduleIntoChat(TARGET, System.currentTimeMillis() + 3_600_000, repeat = null)
        settle { model.state.value.notice != null }

        assertEquals("Rerun the nightly suite", model.state.value.drafts[TARGET.key])
        assertEquals(0L, MobileProtocol.parseObject(sendBodies.single())!!.get("repeatEveryMs")?.asLong ?: 0L)
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
