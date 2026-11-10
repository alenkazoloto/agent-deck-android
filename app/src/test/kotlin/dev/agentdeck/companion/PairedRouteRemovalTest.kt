package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.reach.Route
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PairedRoute
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An address the user removed in Settings stays removed when a dial begun before the removal
 * answers on it afterwards: remembering the answering route must not put it back as preferred.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairedRouteRemovalTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink

    private val lan = Route(Route.LAN, "192.168.1.24", 63350)
    private val tailnet = Route(Route.TAILNET, "100.71.4.9", 63350)
    private val machine = PairedMachine(
        machineName = "workshop", hosts = listOf(lan.host, tailnet.host), port = 63350,
        spkiFingerprint = "ab", token = "t", deviceId = "d",
        explicitRoutes = listOf(PairedRoute(lan, PairedRoute.Source.PAIRED), PairedRoute(tailnet, PairedRoute.Source.PAIRED)),
    )

    private val dialing = CountDownLatch(1)
    private val removed = CountDownLatch(1)
    private val answered = CountDownLatch(1)

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(machine)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { LateTailnet() }
    }

    @After fun tearDown() {
        live.connectionForTest = null
        live.bind(null)
        store.forget(machine.id)
    }

    @Test fun `a dial that answers on a removed address does not bring it back`() {
        live.bind(machine)
        assertTrue("the stale address dial never began", dialing.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(lan), live.removePairedRoute(tailnet)?.routes?.map { it.route })
        removed.countDown()
        assertTrue("the delayed dial never answered", answered.await(5, TimeUnit.SECONDS))

        val held = live.machine!!
        assertNotEquals("the late answer made the removed address preferred", tailnet, held.preferredRoute)
        assertEquals(listOf(lan), held.dialOrder())
        val saved = store.machines().single { it.id == machine.id }
        assertEquals("the store kept the removal", listOf(lan), saved.dialOrder())
    }

    @Test fun `Settings follows a fallback address and lets the old preferred address be removed`() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        store.save(machine.copy(preferredRoute = lan))
        val model = DeckViewModel(app)
        try {
            assertTrue("the fallback dial never began", dialing.await(5, TimeUnit.SECONDS))
            removed.countDown()
            assertTrue("the fallback dial never answered", answered.await(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 5_000
            while (model.state.value.machine?.preferredRoute != tailnet && System.currentTimeMillis() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(5)
            }
            assertEquals("Settings still hides removal of the failed preferred address", tailnet, model.state.value.machine?.preferredRoute)
            model.removePairedRoute(lan)
            assertEquals("Settings kept the removed address", listOf(tailnet), model.state.value.machine?.dialOrder())
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /** Dialed while the address still existed; answers only after the user removed it. */
    private inner class LateTailnet : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = tailnet.host
        override val lastGoodRoute = tailnet
        override fun fleet(): MobileFleetSnapshot {
            dialing.countDown()
            removed.await(5, TimeUnit.SECONDS)
            return MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)
        }
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            answered.countDown()
            closed.await()
        }
        override fun close() = closed.countDown()
    }
}
