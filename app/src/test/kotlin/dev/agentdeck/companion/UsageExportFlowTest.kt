package dev.agentdeck.companion

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileUsageExport
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.LinkPolicy
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

/**
 * Usage export through the view model (P25): the machine's CSV reaches Android's chooser as a
 * `text/csv` file holding exactly its text, under the Agent / Account pick the screen holds; a machine
 * with nothing to export shares nothing and says so; a slow export is waited for, not asked twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageExportFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    @Volatile private var unavailable: String? = null
    @Volatile private var timeOut = false
    private val asked = CopyOnWriteArrayList<Pair<String, Int>>()
    private val queries = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        forgetFileProviderRoots()
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `the chooser receives a CSV file holding the machine's export`() {
        model.exportUsage()
        val chooser = eventually { shadowOf(app).nextStartedActivity }

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/csv", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(CSV, app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
        assertTrue(uri.toString().endsWith("/agents-deck-usage-2026-09-01_2026-09-25.csv"))
        assertEquals(listOf("/v1/usage-export" to LinkPolicy.EXPORT_READ_TIMEOUT_MS), asked.toList())
    }

    @Test fun `the screen's Agent and Account pick is what the machine is asked to cut`() {
        model.filterUsage(AgentVendor.CODEX, "codex-default")
        asked.clear()

        model.exportUsage()
        eventually { shadowOf(app).nextStartedActivity }

        assertEquals("the phone cuts nothing itself", "agent=CODEX&account=codex-default", queries.last())
    }

    @Test fun `a machine with nothing to export shares nothing and says so`() {
        unavailable = "There is no recorded usage to export yet."
        model.exportUsage()
        eventually { model.state.value.snack?.message?.takeUnless { it.startsWith("Preparing") } }

        assertEquals("There is no recorded usage to export yet.", model.state.value.snack?.message)
        assertNull("an empty file would read as a machine that spent nothing", shadowOf(app).nextStartedActivity)
    }

    @Test fun `a slow export is not started again on another address`() {
        timeOut = true
        model.exportUsage()
        eventually { model.state.value.snack?.message?.takeUnless { it.startsWith("Preparing") } }

        assertEquals("one request, to one address", 1, asked.size)
        assertNull("nothing was shared", shadowOf(app).nextStartedActivity)
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

    private inner class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "a.test"
        override fun fleet() = MobileFleetSnapshot(listOf(ROW), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
    }

    private fun bridge() = BridgeClient(listOf("a.test", "b.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getResponseCode(): Int {
                if (url.path != MobileUsageExport.ROUTE) return 404
                asked += url.path to readTimeout
                queries += url.query.orEmpty()
                if (timeOut) throw SocketTimeoutException("Read timed out")
                return 200
            }
            override fun getInputStream(): InputStream =
                (unavailable?.let { MobileUsageExport(unavailable = it) }
                    ?: MobileUsageExport("agents-deck-usage-2026-09-01_2026-09-25.csv", CSV)).toJson().toString().byteInputStream()
            override fun getErrorStream(): InputStream = "{}".byteInputStream()
        }
    }

    private companion object {
        const val CSV = "section,key,sessions\ntotal,2026-09-01..2026-09-25,\n"
        val ROW = MobileFleetRow(
            key = "claude:/repo:session", vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
            projectName = "repo", gitBranch = null, title = "Fix the parser", attention = null, waitingReason = null,
            lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        )
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("a.test", "b.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
