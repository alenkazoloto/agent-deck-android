package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileUsageBucket
import com.github.claudeagents.core.mobile.MobileUsageCard
import com.github.claudeagents.core.mobile.MobileUsageReport
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * Who asks the machine for its spend, and when — the composition, not the screen.
 *
 * `UsageScreenTest` proves the page draws what it is handed and asks once per visit;
 * that is invisible to the defect this pins. The Usage page is **pushed**, so
 * `Navigation.toJson` persists `agentdeck://usage` and Android killing the process lands the
 * user straight back on it — with no `hello` yet, because the restore refreshed capabilities
 * for Scheduled and Settings alone. `loadUsage` is gated on the `usage` capability, the gate
 * read an empty list, the one `LaunchedEffect(Unit)` had already fired, and the page settled on
 * "This machine reported no usage." over a machine with a year of spend. Permanently: nothing
 * re-drives a `LaunchedEffect(Unit)`.
 *
 * So both halves are asserted here — the restore asks for `hello`, and the capability *becoming*
 * true re-drives the fetch — because either one alone leaves a way into the stranded page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageFetchTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore

    /** Every route this fake machine was asked for, in order. */
    private val asked = mutableListOf<String>()

    /** Whether this machine admits to serving `/v1/usage` at all. */
    private var advertisesUsage = true

    private var current: DeckViewModel? = null

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
    }

    @After fun tearDown() {
        store.forget(MACHINE.id)
        store.saveScreen(null)
        Dispatchers.resetMain()
    }

    /**
     * The first half, where the defect started: a restore lands on a persisted screen whose own
     * fetch is capability-gated, and nothing asked the machine what it can do. `Screen.Usage`
     * was missing from that list, which is the list — not the pattern — that goes stale.
     */
    @Test fun `every screen whose fetch is capability-gated owes a hello on restore`() {
        assertTrue(Navigation.needsHello(Screen.Usage))
        assertTrue(Navigation.needsHello(Screen.Settings))
        assertTrue(Navigation.needsHello(Screen.Scheduled))
        // Fleet's data rides the stream and a conversation refreshes through its own path, so
        // neither owes the extra round trip. A back stack restores into Usage, though, which is
        // the whole reason this is a list rather than a condition at one call site.
        assertTrue(Navigation.needsHello(Navigation.fromJson(Navigation.toJson(Screen.Usage))!!))
    }

    /**
     * The second half, and the one the first cannot cover. Even with hello refreshed, the page's
     * single `LaunchedEffect(Unit)` has already fired and been declined by the time the
     * capability arrives — so the capability *turning true* has to be a trigger of its own, the
     * shape `discoveredPaging` beside it already had. Before the fix this run asks for hello and
     * stops; `asked` never contains `/v1/usage` and the page stays empty.
     */
    @Test fun `the usage capability arriving drives the fetch the empty gate declined`() =
        runBlocking {
            store.saveScreen(Navigation.toJson(Screen.Usage))
            val model = model()
            settle()
            assertEquals(Screen.Usage, model.state.value.screen)
            assertNull("the fixture must start with no capabilities known", model.state.value.hello)

            // What the restore does once it has a client: ask, and let the answer re-drive.
            model.refreshHello()
            settle()

            assertTrue(
                "the page must end up with figures, not with an empty gate; asked: $asked",
                asked.any { it.endsWith(MobileUsageReport.ROUTE) },
            )
            val report = model.state.value.usage
            assertNotNull("the fetched report must reach the state", report)
            assertEquals(listOf("Today"), report!!.cards.map { it.label })
        }

    /** The negative control: an older machine names no capability and is asked for nothing. */
    @Test fun `a machine that does not advertise usage is never asked for it`() = runBlocking {
        advertisesUsage = false
        store.saveScreen(Navigation.toJson(Screen.Usage))
        val model = model()
        settle()

        model.refreshHello()
        settle()

        assertTrue(
            "a surface the machine did not advertise must cost no request; asked: $asked",
            asked.none { it.endsWith(MobileUsageReport.ROUTE) },
        )
        assertNull(model.state.value.usage)
    }

    // ---- harness -----------------------------------------------------------------------

    private suspend fun settle() {
        repeat(40) { kotlinx.coroutines.delay(5) }
    }

    private fun model() = DeckViewModel(app)
        .also { it.connectionForTest = { fakeBridge() }; current = it }

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
            override fun getResponseCode(): Int = 200
            override fun getErrorStream(): InputStream = "{}".byteInputStream()
            override fun getInputStream(): InputStream {
                asked += url.path
                return when {
                    url.path.endsWith("/v1/hello") -> hello()
                    url.path.endsWith(MobileUsageReport.ROUTE) -> usage()
                    else -> "{}"
                }.byteInputStream()
            }
        }
    }

    private fun hello(): String {
        val capabilities = buildList {
            add(MobileProtocol.Capability.FLEET)
            if (advertisesUsage) add(MobileProtocol.Capability.USAGE)
        }
        return com.google.gson.JsonObject().apply {
            addProperty("v", MobileProtocol.VERSION)
            addProperty("machine", "workshop")
            addProperty("ide", "IntelliJ IDEA")
            addProperty("pluginVersion", "1.0.0")
            add("capabilities", com.google.gson.JsonArray().also { arr -> capabilities.forEach(arr::add) })
        }.toString()
    }

    private fun usage(): String = MobileUsageReport(
        cards = listOf(MobileUsageCard("Today", MobileUsageBucket(costUsd = 3.17))),
        generatedAtMs = 1_785_532_440_000,
    ).toJson().toString()

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "workshop", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-usage",
        )
    }
}
