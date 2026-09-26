package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionDeletePreview
import com.github.claudeagents.core.mobile.MobileSessionDeleteRequest
import com.github.claudeagents.core.mobile.MobileSessionDeleteResult
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
import org.junit.Assert.assertNull
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
 * Delete through the view model (M3, P06): the machine's preview is what the reader confirms, the
 * confirmation carries that preview's token and nothing else, a deleted row leaves the list, and a
 * chat that changed since is not deleted — the machine is asked again and the new preview shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDeleteFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    /** Each request's body, as the machine received it. */
    private val asked = CopyOnWriteArrayList<MobileSessionDeleteRequest>()
    @Volatile private var token = "t1"
    @Volatile private var staleOnce = false
    @Volatile private var refused: String? = null

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
        eventually { model.state.value.snapshot?.rows?.takeIf { it.isNotEmpty() } }
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `the confirmed preview deletes that chat and the row leaves the list`() {
        model.previewDelete(ROW)
        val preview = eventually { model.state.value.deletePreview }
        assertEquals("Fix the parser", preview.title)

        model.confirmDelete(preview)
        eventually { model.state.value.snack?.message?.takeIf { it.startsWith("Moved") } }

        assertEquals(
            listOf(MobileSessionDeleteRequest(ROW.key), MobileSessionDeleteRequest(ROW.key, "t1")),
            asked.toList(),
        )
        assertNull(model.state.value.deletePreview)
        eventually { model.state.value.snapshot?.takeIf { snapshot -> snapshot.rows.none { it.key == ROW.key } } }
    }

    @Test fun `a chat that changed since the preview is not deleted and is previewed again`() {
        staleOnce = true
        model.previewDelete(ROW)
        val first = eventually { model.state.value.deletePreview }
        token = "t2"

        model.confirmDelete(first)
        val second = eventually { model.state.value.deletePreview?.takeIf { it.previewToken == "t2" } }

        assertEquals(MobileRefusal.DELETE_PREVIEW_STALE.message, model.state.value.snack?.message)
        assertEquals(
            "preview, the refused confirmation, then a fresh preview — never a second delete",
            listOf(MobileSessionDeleteRequest(ROW.key), MobileSessionDeleteRequest(ROW.key, "t1"), MobileSessionDeleteRequest(ROW.key)),
            asked.toList(),
        )
        assertEquals("t2", second.previewToken)
    }

    @Test fun `a machine that will not delete now says why and opens no dialog`() {
        refused = "Another process is writing this chat. Nothing was deleted."
        model.previewDelete(ROW)
        eventually { model.state.value.snack?.message?.takeIf { it == refused } }

        assertNull("a refusal is never a confirmation dialog", model.state.value.deletePreview)
        assertEquals(listOf(MobileSessionDeleteRequest(ROW.key)), asked.toList())
    }

    @Volatile private var deleted = false

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    private inner class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "a.test"
        override fun fleet() = MobileFleetSnapshot(if (deleted) emptyList() else listOf(ROW), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private var status = 404
            private var answer = "{}"
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileSessionDeleteRequest.ROUTE) return 404
                val request = MobileSessionDeleteRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                asked += request
                val confirmed = request.previewToken
                when {
                    confirmed == null -> {
                        status = 200
                        answer = (refused?.let { MobileSessionDeletePreview(ROW.key, ROW.title, false, 0, 0, "", refused = it) }
                            ?: MobileSessionDeletePreview(ROW.key, ROW.title, true, 1, 0, token)).toJson().toString()
                    }
                    staleOnce -> {
                        staleOnce = false
                        status = MobileRefusal.DELETE_PREVIEW_STALE.status
                        answer = MobileRefusal.DELETE_PREVIEW_STALE.toJson().toString()
                    }
                    else -> {
                        deleted = true
                        status = 200
                        answer = MobileSessionDeleteResult(ROW.key, true, "Chat moved to Trash.").toJson().toString()
                    }
                }
                return status
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
