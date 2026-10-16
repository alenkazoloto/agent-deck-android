package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.LinkPolicy
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * R1 of `PLAN-MULTI-MACHINE.md`: a new default network moves a live stream make-before-break.
 * Before, `onAvailable` stopped the stream, then reconnected and refetched the fleet, so walking
 * from Wi-Fi onto cellular dropped a stream the old network was still carrying.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkHandoverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = SecureStore(app).also { it.save(MACHINE) }
    private val live = LiveLink.of(app).also { it.bind(null) }
    private val events = CopyOnWriteArrayList<String>()
    private val fleets = AtomicInteger()
    private val opened = AtomicInteger()
    private val streams = CopyOnWriteArrayList<Stream>()

    @After fun tearDown() {
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        streams.forEach { it.close() }
    }

    @Test fun `a live stream moves to the new network before the old one closes`() {
        live.connectionForTest = { Stream(opened.incrementAndGet(), delivers = true).also { streams += it } }
        live.bind(MACHINE)
        eventually { "live:1" in events }

        live.handover()

        eventually { "closed:1" in events }
        assertTrue(
            "the new stream must be live before the old one closes: $events",
            events.indexOf("live:2") in 0 until events.indexOf("closed:1"),
        )
        assertEquals("a move reads the fleet off the stream, it does not refetch", 1, fleets.get())
        assertEquals(Link.Live, live.link.value)
    }

    @Test fun `with no live stream a new network reconnects at once`() {
        live.connectionForTest = { Stream(opened.incrementAndGet(), delivers = false).also { streams += it } }
        live.bind(MACHINE)
        eventually { "failed:1" in events }

        live.handover()

        eventually(withinMs = LinkPolicy.FIRST_BACKOFF_MS / 2) { opened.get() >= 2 }
        assertTrue("a reconnect refetches the fleet: $events", fleets.get() >= 2)
    }

    private fun eventually(withinMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held: $events")
    }

    inner class Stream(private val n: Int, private val delivers: Boolean) : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "machine.test"
        override fun fleet(): MobileFleetSnapshot {
            fleets.incrementAndGet()
            return MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)
        }
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            if (!delivers) {
                events += "failed:$n"
                throw IOException("no route")
            }
            onAlive()
            events += "live:$n"
            closed.await()
            throw IOException("closed")
        }
        override fun close() {
            if (closed.count > 0) events += "closed:$n"
            closed.countDown()
        }
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
