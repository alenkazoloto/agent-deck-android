package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileFolder
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import com.github.claudeagents.core.mobile.MobileSessionActionResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

/**
 * Folders through the view model (M3, P05): a move or a new folder is one `/v1/session-actions`
 * request, the row and the folder list take the machine's answer (a new folder's id is the
 * machine's), a move's Undo files the chat back where it was, and a refusal is said, not drawn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionFoldersFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    private val asked = CopyOnWriteArrayList<MobileSessionActionRequest>()
    @Volatile private var folders = listOf(MobileFolder("a", "Alpha"), MobileFolder("b", "Beta"))
    @Volatile private var filedIn: String? = "a"
    @Volatile private var refusal: MobileRefusal? = null

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
        eventually { model.state.value.snapshot?.rows?.takeIf { it.isNotEmpty() } }
        // A cached frame can arrive before the link is up; acting then races the machine switch
        // that drops a reply from an older generation.
        eventually { model.state.value.link.takeIf { it == Link.Live } }
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a new folder takes the machine's id and offers no Undo that would strand it`() {
        val row = eventually { model.state.value.snapshot?.rows?.singleOrNull() }
        model.fileInFolder(row, null, newFolder = "Bug bash")
        val snack = eventually { model.state.value.snack?.takeIf { it.message == "Created \"Bug bash\" and moved the chat there" } }

        val snapshot = model.state.value.snapshot!!
        assertEquals("m1", snapshot.rows.single().folderId)
        assertEquals(listOf(MobileFolder("a", "Alpha"), MobileFolder("b", "Beta"), MobileFolder("m1", "Bug bash")), snapshot.folders)
        assertEquals(null, snack.undoLabel)
        assertEquals(listOf(MobileSessionActionRequest(ROW.key, MobileSessionActionRequest.NEW_FOLDER, title = "Bug bash")), asked.toList())
    }

    @Test fun `a move offers Undo, which files the chat back`() {
        val row = eventually { model.state.value.snapshot?.rows?.singleOrNull() }
        model.fileInFolder(row, "b")
        eventually { model.state.value.snack?.takeIf { it.message == "Moved to \"Beta\"" && it.undoLabel == "Undo" } }

        model.undoSnack()
        eventually { model.state.value.snapshot?.rows?.singleOrNull()?.takeIf { it.folderId == "a" } }
        assertEquals(
            listOf(
                MobileSessionActionRequest(ROW.key, MobileSessionActionRequest.FOLDER, folderId = "b"),
                MobileSessionActionRequest(ROW.key, MobileSessionActionRequest.FOLDER, folderId = "a"),
            ),
            asked.toList(),
        )
    }

    @Test fun `taking a chat out sends a blank folder`() {
        val row = eventually { model.state.value.snapshot?.rows?.singleOrNull() }
        model.fileInFolder(row, "")
        eventually { model.state.value.snack?.message?.takeIf { it == "Removed from its folder" } }

        assertEquals(listOf(MobileSessionActionRequest(ROW.key, MobileSessionActionRequest.FOLDER, folderId = "")), asked.toList())
        assertEquals(null, model.state.value.snapshot!!.rows.single().folderId)
    }

    @Test fun `a folder deleted at the desk is refused by name and the row stays put`() {
        refusal = MobileRefusal.FOLDER_GONE
        val row = eventually { model.state.value.snapshot?.rows?.singleOrNull() }
        model.fileInFolder(row, "gone")
        eventually { model.state.value.snack?.message?.takeIf { it == MobileRefusal.FOLDER_GONE.message } }

        assertEquals("a", model.state.value.snapshot!!.rows.single().folderId)
    }

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    /** The machine's fleet reflects its own folder store, as the real frame does after a write. */
    private inner class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "a.test"
        override fun fleet() = MobileFleetSnapshot(listOf(ROW.copy(folderId = filedIn)), 0, emptyList(), null, 1, folders = folders)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private var answer = "{}"
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileSessionActionRequest.ROUTE) return 404
                val request = MobileSessionActionRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                asked += request
                refusal?.let {
                    answer = it.toJson().toString()
                    return it.status
                }
                if (request.action == MobileSessionActionRequest.NEW_FOLDER) {
                    folders = folders + MobileFolder("m1", request.title.orEmpty())
                    filedIn = "m1"
                } else {
                    filedIn = request.folderId?.takeIf { it.isNotBlank() }
                }
                answer = MobileSessionActionResult(ROW.key, ROW.title, false, false, filedIn, folders).toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        val ROW = MobileFleetRow(
            key = "claude:/repo:session", vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
            projectName = "repo", gitBranch = null, title = "Fix the parser", attention = null, waitingReason = null,
            lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        )
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("a.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
