package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.ScheduleDuplicate
import dev.agentdeck.companion.data.ScheduleRepeat
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.fixture.DeckFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * The desk's "Duplicate…" through the view model and a real [BridgeClient]: the row is read the
 * way an edit reads it, the copy's prompt lives in its own draft beside the blank dialog's, and
 * a schedule sends the source's run choices as a new chat — leaving the draft where it was typed
 * when the machine refuses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleDuplicateFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private var detail: MobileScheduleEditDetail = DETAIL
    private var detailError: Pair<Int, String>? = null
    private var sendReply: Pair<Int, String> = 200 to """{"v":1,"taskId":"t-2","state":"queued"}"""
    private val sendBodies = mutableListOf<String>()
    private val detailReads = mutableListOf<String>()
    private var transcriptReads = 0
    private var hello: String = DeckFixtures.byName("scheduled")!!.hello!!.let {
        it.copy(capabilities = it.capabilities + listOf(MobileProtocol.Capability.SCHEDULE_EDIT, MobileProtocol.Capability.SCHEDULE_CREATE))
    }.toJson().toString()
    private var lastModel: DeckViewModel? = null

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

    private val draftKey = DeckViewModel.scheduleDuplicateDraftKey(DETAIL.id)

    @Test fun `Duplicate reads the row and opens the copy on its prompt, in a draft of its own`() {
        val model = opened()
        model.setDraft(DeckViewModel.SCHEDULE_DRAFT_KEY, "A prompt typed into the blank dialog")

        model.duplicateSchedule(DETAIL.id)
        settle { model.state.value.scheduleDuplicate != null }

        assertEquals(listOf(DETAIL.id), detailReads)
        assertEquals(DETAIL.prompt, model.state.value.drafts[draftKey])
        assertEquals("A prompt typed into the blank dialog", model.state.value.drafts[DeckViewModel.SCHEDULE_DRAFT_KEY])
    }

    @Test fun `a copy the reader edited and closed comes back as they left it`() {
        val model = opened()
        model.duplicateSchedule(DETAIL.id)
        settle { model.state.value.scheduleDuplicate != null }
        model.setDraft(draftKey, "Audit only the lockfile")
        model.closeScheduleDuplicate()
        assertNull(model.state.value.scheduleDuplicate)

        model.duplicateSchedule(DETAIL.id)
        settle { model.state.value.scheduleDuplicate != null }

        assertEquals("Audit only the lockfile", model.state.value.drafts[draftKey])
    }

    @Test fun `scheduling the copy sends a new chat with the source's choices and consumes only its own draft`() {
        val model = opened()
        model.setDraft(DeckViewModel.SCHEDULE_DRAFT_KEY, "Blank dialog text")
        model.duplicateSchedule(DETAIL.id)
        settle { model.state.value.scheduleDuplicate != null }
        val due = System.currentTimeMillis() + 3_600_000

        model.createSchedule(ScheduleDuplicate.target(DETAIL)!!, due, ScheduleRepeat(atTime = "09:00"), draftKey = draftKey)
        settle { model.state.value.snack != null }

        val body = MobileProtocol.parseObject(sendBodies.single())!!
        assertEquals(true, body.get("newChat")?.asBoolean)
        assertNull(body.get("key")?.takeIf { !it.isJsonNull })
        assertEquals("/repo", body.get("projectPath")?.asString)
        assertEquals(DETAIL.prompt, body.get("prompt")?.asString)
        assertEquals("sonnet", body.get("model")?.asString)
        assertEquals(due, body.get("dueAtMs")?.asLong)
        assertEquals("09:00", body.get("repeatAtTime")?.asString)
        assertEquals("", model.state.value.drafts[draftKey].orEmpty())
        assertEquals("Blank dialog text", model.state.value.drafts[DeckViewModel.SCHEDULE_DRAFT_KEY])
    }

    @Test fun `a refused copy keeps its draft and says why`() {
        sendReply = 503 to """{"v":1,"error":"unavailable","message":"The IDE is busy."}"""
        val model = opened()
        model.duplicateSchedule(DETAIL.id)
        settle { model.state.value.scheduleDuplicate != null }

        model.createSchedule(ScheduleDuplicate.target(DETAIL)!!, System.currentTimeMillis() + 3_600_000, draftKey = draftKey)
        settle { model.state.value.notice != null }

        assertEquals(DETAIL.prompt, model.state.value.drafts[draftKey])
    }

    @Test fun `a prompt that continues a chat is not copied, and the reader is told`() {
        detail = DETAIL.copy(sessionId = "abc")
        val model = opened()

        model.duplicateSchedule(DETAIL.id)
        settle { detailReads.isNotEmpty() && model.state.value.snack != null }

        assertNull(model.state.value.scheduleDuplicate)
        assertNull(model.state.value.drafts[draftKey])
        assertTrue(model.state.value.snack!!.message.contains("not duplicated"))
    }

    @Test fun `a row the machine can no longer read opens no dialog and says why`() {
        detailError = 404 to """{"v":1,"error":"not_found","message":"That prompt is gone."}"""
        val model = opened()

        model.duplicateSchedule(DETAIL.id)
        settle { model.state.value.notice != null }

        assertNull(model.state.value.scheduleDuplicate)
        assertNotNull(model.state.value.notice)
    }

    @Test fun `a plugin without schedule-edit is told to update, and nothing is read`() {
        hello = "{}"
        val model = opened()

        model.duplicateSchedule(DETAIL.id)
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.startsWith("Update the IDE plugin"))
        assertTrue(detailReads.isEmpty())
        assertNull(model.state.value.scheduleDuplicate)
    }

    // ---- harness (as ScheduleIntoChatSendTest) -------------------------------------------

    private fun opened(): DeckViewModel {
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }
        lastModel = model
        model.open(DeepLink.Conversation(TARGET.key, TARGET.title, TARGET.vendor, TARGET.projectPath))
        settle { transcriptReads > 0 && model.state.value.hello != null }
        return model
    }

    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            ShadowLooper.idleMainLooper()
            if (until()) return
        }
        throw AssertionError("never settled: reads=$detailReads notice=${lastModel?.state?.value?.notice} snack=${lastModel?.state?.value?.snack}")
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
                url.path.endsWith("/v1/hello") -> 200 to hello
                url.path.startsWith("/v1/scheduled/") -> detailError ?: (200 to detail.toJson().toString())
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.startsWith("/v1/session/")) transcriptReads++
                if (url.path.startsWith("/v1/scheduled/")) detailReads += url.path.substringAfterLast('/')
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
        val DETAIL = MobileScheduleEditDetail(
            "s-new", "Nightly dependency audit", "/repo", null, 1_800_000_000_000,
            0, "09:00", "sonnet", AgentVendor.CLAUDE.name, "work", null, "Europe/Amsterdam", true, true,
            effort = "high", permissionMode = "plan",
        )
    }
}
