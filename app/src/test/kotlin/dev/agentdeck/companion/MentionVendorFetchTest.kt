package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFileList
import com.github.claudeagents.core.mobile.MobileFileQuery
import com.github.claudeagents.core.mobile.MobileMentionRow
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * M2 P15: the `@` query names the chat's agent, because only a Codex chat is offered Codex's
 * plugins (`MobileFileSearch.codexPluginRows`), and the plugin row survives the decode an older
 * phone would drop it at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MentionVendorFetchTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private val asked = mutableListOf<String>()

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

    @Test fun `a Codex chat's @ query names its vendor and gets the plugin row back`() = runBlocking {
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }

        val matches = model.searchFiles("/work/app", "pd", key = "k", vendor = AgentVendor.CODEX)

        assertTrue("asked: $asked", asked.single().contains("&vendor=CODEX"))
        assertEquals(listOf(MobileMentionRow.Kind.CODEX_PLUGIN to "PDF"), matches.context.map { it.kind to it.token })
    }

    @Test fun `a query that names no vendor sends none`() = runBlocking {
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }

        model.searchFiles("/work/app", "pd", key = "k")

        assertTrue("asked: $asked", asked.single().let { "vendor=" !in it })
    }

    private fun fakeBridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode(): Int = 200
            override fun getErrorStream(): InputStream = "{}".byteInputStream()
            override fun getInputStream(): InputStream {
                if (!url.path.endsWith(MobileFileQuery.ROUTE)) return "{}".byteInputStream()
                asked += url.query
                return MobileFileList(
                    project = "/work/app",
                    query = "pd",
                    paths = emptyList(),
                    context = listOf(MobileMentionRow("PDF", "PDF", "Read, create, and verify PDF files", MobileMentionRow.Kind.CODEX_PLUGIN)),
                ).toJson().toString().byteInputStream()
            }
        }
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "workshop", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-mention-vendor",
        )
    }
}
