package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * t3code #8618: a tapped Stop said nothing, and a Stop the IDE declined left "Working…" on screen
 * with no explanation. The view model now holds the conversation as `stopping` while the request
 * is out, and puts the machine's own refusal sentence up as the notice when it answers one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopFeedbackTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    /** Held until the test lets the machine answer `/v1/stop`. */
    private val answer = CountDownLatch(1)
    private var stopStatus = 409
    private var stopBody =
        """{"v":1,"error":"run-not-owned","message":"That run already finished or is owned by another window."}"""

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
    }

    @After fun tearDown() {
        answer.countDown()
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a tapped Stop is stopping until the machine answers, then the refusal is the notice`() {
        model.stop(KEY)

        assertTrue("nothing showed that Stop was tapped", KEY in model.state.value.stopping)
        model.stop(KEY)
        assertEquals("a second tap sent a second Stop", setOf(KEY), model.state.value.stopping)

        answer.countDown()
        eventually { KEY !in model.state.value.stopping }

        assertEquals(
            "That run already finished or is owned by another window.",
            model.state.value.notice,
        )
    }

    @Test fun `a Stop the machine delivers clears stopping without a notice`() {
        stopStatus = 200
        stopBody = """{"v":1,"accepted":true,"stopped":true}"""

        model.stop(KEY)
        answer.countDown()
        eventually { KEY !in model.state.value.stopping }

        assertNull(model.state.value.notice)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    private class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "test-machine"
        override fun fleet() = MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
    }

    private fun bridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = body
            override fun getResponseCode(): Int {
                if (!url.path.startsWith("/v1/stop")) return 200
                answer.await(5, TimeUnit.SECONDS)
                return stopStatus
            }
            override fun getErrorStream(): InputStream = stopBody.byteInputStream()
            override fun getInputStream(): InputStream = when {
                url.path.startsWith("/v1/stop") -> stopBody
                url.path.startsWith("/v1/session/") -> PAGE
                else -> "{}"
            }.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude:/repo:session"
        const val PAGE = """{"v":1,"key":"$KEY","title":"Fix the parser","turns":[],""" +
            """"hasMore":false,"costUsd":0.0,"costKnown":false,"running":false,"generatedAtMs":1}"""
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
