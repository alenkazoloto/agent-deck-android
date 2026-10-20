package dev.agentdeck.companion

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobilePairingPayload
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SecureStore
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.robolectric.shadows.ShadowLooper
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.PairingConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PairingConfirmationTest {
    @get:Rule val compose = createComposeRule()
    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `QR waits for confirmation and preserves the chosen phone name`() {
        val model = DeckViewModel(ApplicationProvider.getApplicationContext<Application>())
        assertTrue(model.pairFromQr(PAYLOAD.encode(), "Work phone"))
        assertEquals(PAYLOAD, model.state.value.pendingPairing)
        assertEquals("Work phone", model.state.value.pendingPairingLabel)
        assertFalse("scanning must not start a network exchange", model.state.value.pairing)
        assertNull(model.state.value.machine)
        assertFalse(model.pairFromQr("not a pairing code", "Ignored"))
        assertEquals(PAYLOAD, model.state.value.pendingPairing)
        model.dismissPendingPairing()
        assertNull(model.state.value.pendingPairing)
        assertNull(model.state.value.pendingPairingLabel)
    }

    @Test fun `an incoming link replaces a scanned label with the default`() {
        val model = DeckViewModel(ApplicationProvider.getApplicationContext<Application>())
        model.pairFromQr(PAYLOAD.encode(), "Work phone")
        model.receivePairing(PAYLOAD.copy(machineName = "Other computer"))
        assertEquals("Other computer", model.state.value.pendingPairing?.machineName)
        assertNull(model.state.value.pendingPairingLabel)
        assertFalse(model.state.value.pairing)
    }

    @Test fun `a delayed pairing response cannot dismiss a newer QR confirmation`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = DeckViewModel(app)
        val entered = CountDownLatch(1)
        val respond = CountDownLatch(1)
        model.connectionForTest = {
            BridgeClient(PAYLOAD.hosts, PAYLOAD.port, PAYLOAD.spkiFingerprint, null) { url ->
                object : HttpsURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getCipherSuite() = "test"
                    override fun getLocalCertificates(): Array<Certificate>? = null
                    override fun getServerCertificates(): Array<Certificate> = emptyArray()
                    override fun getOutputStream() = java.io.ByteArrayOutputStream()
                    override fun getResponseCode(): Int {
                        entered.countDown()
                        check(respond.await(5, TimeUnit.SECONDS))
                        return 200
                    }
                    override fun getInputStream() =
                        """{"v":1,"token":"paired-token","deviceId":"phone","machine":"Work laptop"}""".byteInputStream()
                }
            }
        }
        try {
            model.receivePairing(PAYLOAD)
            model.confirmPendingPairing("Work phone")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val next = PAYLOAD.copy(machineName = "Other computer", code = "87654321")
            model.pairFromQr(next.encode(), "Second phone name")
            respond.countDown()
            val deadline = System.currentTimeMillis() + 5_000
            while (model.state.value.pairing && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
                ShadowLooper.idleMainLooper()
            }
            assertFalse(model.state.value.pairing)
            assertEquals("Work laptop", model.state.value.machine?.machineName)
            assertEquals(next, model.state.value.pendingPairing)
            assertEquals("Second phone name", model.state.value.pendingPairingLabel)
        } finally {
            respond.countDown()
            LiveLink.of(app).bind(null)
            SecureStore(app).machines().forEach { SecureStore(app).forget(it.id) }
        }
    }

    @Test fun `confirmation names the host and access boundary before connecting`() {
        var confirmed: String? = null
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                PairingConfirmDialog(
                    payload = PAYLOAD, initialLabel = "Work phone", alreadyPaired = false,
                    pairing = false, pairError = null, onConfirm = { confirmed = it },
                    onDismiss = {}, onDismissError = {},
                )
            }
        }
        compose.onNodeWithText("Pair with Work laptop").assertIsDisplayed()
        compose.onNodeWithText("Access all supported chats across projects on this computer, including new chats. " +
            "Chats stay on the computer. Keep it awake with its IDE running.").assertIsDisplayed()
        assertNull(confirmed)
        compose.onNodeWithText("Pair").performClick()
        assertEquals("Work phone", confirmed)
    }

    companion object {
        val PAYLOAD = MobilePairingPayload(listOf("100.71.4.9"), 63350, "a".repeat(43), "12345678", "Work laptop")
    }
}
