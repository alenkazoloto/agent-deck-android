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
import com.github.claudeagents.core.mobile.MobileFolderActionRequest
import com.github.claudeagents.core.mobile.MobileFolderActionResult
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
 * Folder editing through the view model (M3, P05): one `/v1/folder-actions` request, the folder
 * list takes the machine's answer, a delete unfiles the folder's rows at once, and a refusal is
 * said, not drawn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderActionsFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    private val asked = CopyOnWriteArrayList<MobileFolderActionRequest>()
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

    @Test fun `an edit takes the machine's folder list and says so`() {
        eventually { model.state.value.snapshot?.rows?.singleOrNull() }
        model.folderAction(MobileFolderActionRequest("a", MobileFolderActionRequest.EDIT, name = "Gamma"))
        eventually { model.state.value.snack?.message?.takeIf { it == "Saved the folder \"Gamma\"" } }

        assertEquals(listOf(MobileFolder("a", "Gamma"), MobileFolder("b", "Beta")), model.state.value.snapshot!!.folders)
        assertEquals(listOf(MobileFolderActionRequest("a", MobileFolderActionRequest.EDIT, name = "Gamma")), asked.toList())
    }

    @Test fun `a delete unfiles the folder's rows and offers no Undo`() {
        eventually { model.state.value.snapshot?.rows?.singleOrNull()?.takeIf { it.folderId == "a" } }
        model.folderAction(MobileFolderActionRequest("a", MobileFolderActionRequest.DELETE))
        val snack = eventually { model.state.value.snack?.takeIf { it.message == "Deleted the folder \"Alpha\"" } }

        assertEquals(null, snack.undoLabel)
        assertEquals(listOf(MobileFolder("b", "Beta")), model.state.value.snapshot!!.folders)
        assertEquals(null, model.state.value.snapshot!!.rows.single().folderId)
    }

    @Test fun `a folder deleted at the desk is refused by name and nothing changes`() {
        refusal = MobileRefusal.FOLDER_GONE
        eventually { model.state.value.snapshot?.rows?.singleOrNull() }
        model.folderAction(MobileFolderActionRequest("a", MobileFolderActionRequest.DELETE))
        eventually { model.state.value.snack?.message?.takeIf { it == MobileRefusal.FOLDER_GONE.message } }

        assertEquals("a", model.state.value.snapshot!!.rows.single().folderId)
        assertEquals(2, model.state.value.snapshot!!.folders.size)
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
                if (url.path != MobileFolderActionRequest.ROUTE) return 404
                val request = MobileFolderActionRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                asked += request
                refusal?.let {
                    answer = it.toJson().toString()
                    return it.status
                }
                if (request.action == MobileFolderActionRequest.DELETE) {
                    folders = folders.filterNot { it.id == request.folderId }
                    if (filedIn == request.folderId) filedIn = null
                } else {
                    folders = folders.map { if (it.id == request.folderId) it.copy(name = request.name ?: it.name) else it }
                }
                answer = MobileFolderActionResult(folders).toJson().toString()
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
