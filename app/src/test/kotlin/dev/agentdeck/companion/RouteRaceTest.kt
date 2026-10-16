package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.reach.ReprobePolicy
import com.github.claudeagents.core.reach.Route
import com.github.claudeagents.core.reach.RouteDialer
import com.github.claudeagents.core.reach.RouteMemory
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.Handshake
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.LinkPolicy
import dev.agentdeck.companion.data.LinkRecovery
import dev.agentdeck.companion.data.NetworkFingerprint
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PairedRoute
import dev.agentdeck.companion.data.PinMismatchException
import dev.agentdeck.companion.data.RouteReach
import dev.agentdeck.companion.data.StreamReprobe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

/**
 * R1 of `PLAN-MULTI-MACHINE.md` on the phone: every request reaches the machine through
 * [RouteReach], which races handshakes with the shared `core/reach` dialer, reuses the winner per
 * pairing and network, and remembers what failed. The stream moves back to a better route
 * make-before-break, and only while it is open.
 */
class RouteRaceTest {

    private val lan = Route(Route.LAN, "192.168.1.24", 63350)
    private val tailnet = Route(Route.TAILNET, "100.101.1.2", 63350)
    private val relay = Route(Route.RELAY, "k3x.relay.example", 443)

    private val pool = Executors.newCachedThreadPool()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @After fun tearDown() {
        pool.shutdownNow()
        scheduler.shutdownNow()
    }

    private fun machine(vararg routes: Route) = PairedMachine(
        machineName = "desk", hosts = routes.map { it.host }, port = 63350, spkiFingerprint = "00",
        token = "t", deviceId = "device-1",
        explicitRoutes = routes.map { PairedRoute(it, PairedRoute.Source.PAIRED) },
    )

    /** A timer that never fires: only the first route starts unless one fails. Deterministic. */
    private class HeldTimer : RouteDialer.Timer {
        val delays = CopyOnWriteArrayList<Long>()
        val tasks = CopyOnWriteArrayList<Runnable>()
        val cancelled = AtomicInteger()
        override fun after(delayMs: Long, task: Runnable): RouteDialer.Cancellable {
            delays += delayMs
            tasks += task
            return RouteDialer.Cancellable { cancelled.incrementAndGet() }
        }
    }

    private fun reach(timer: RouteDialer.Timer = RouteDialer.timer(scheduler)) = RouteReach(pool, timer)

    private class Shaken {
        val routes = CopyOnWriteArrayList<Route>()
        val closed = AtomicInteger()
        fun by(behave: (Route) -> Unit): Handshake = { route ->
            routes += route
            behave(route)
            AutoCloseable { closed.incrementAndGet() }
        }
    }

    private open class Connection(url: URL) : HttpsURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = """{"hosts":[]}""".byteInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
    }

    private fun answering(requests: MutableList<String>): (URL) -> HttpsURLConnection = { url ->
        requests += url.host
        Connection(url)
    }

    // ---- the race ---------------------------------------------------------------------

    @Test fun `a dead first route costs one stagger, then the next wins and the late connection is closed`() {
        val release = CountDownLatch(1)
        val lateClosed = CountDownLatch(1)
        val handshake: Handshake = { route ->
            if (route == lan) {
                // A LAN address from cellular: the connect hangs until its own timeout.
                release.await(10, TimeUnit.SECONDS)
                AutoCloseable { lateClosed.countDown() }
            } else AutoCloseable {}
        }
        val requests = CopyOnWriteArrayList<String>()
        val client = BridgeClient(machine(lan, relay), reach(), handshake, answering(requests))

        val started = System.nanoTime()
        client.hosts()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals("only the winner carries the request", listOf(relay.host), requests)
        assertEquals(relay, client.lastGoodRoute)
        assertTrue("the next route starts after the stagger ($elapsedMs ms)", elapsedMs >= RouteDialer.STAGGER_MS - 20)
        assertTrue("a dead route must not cost its connect timeout ($elapsedMs ms)", elapsedMs < LinkPolicy.CONNECT_TIMEOUT_MS / 2)
        release.countDown()
        assertTrue("a loser that connects late is closed, not leaked", lateClosed.await(5, TimeUnit.SECONDS))
    }

    @Test fun `the winner is reused by later requests and other clients without racing again`() {
        val timer = HeldTimer()
        val reach = reach(timer)
        val shaken = Shaken()
        val handshake = shaken.by { }
        val requests = CopyOnWriteArrayList<String>()

        BridgeClient(machine(lan, relay), reach, handshake, answering(requests)).hosts()
        val armedByRace = timer.delays.size
        val second = BridgeClient(machine(lan, relay), reach, handshake, answering(requests))
        second.hosts()
        second.hosts()

        assertEquals("one race per pairing and network", listOf(lan), shaken.routes)
        assertEquals("the race's probe socket is closed once the route is known", 1, shaken.closed.get())
        assertEquals(listOf(lan.host, lan.host, lan.host), requests)
        assertEquals("a reused winner schedules nothing", armedByRace, timer.delays.size)
        assertEquals(lan, reach.winner("device-1"))
    }

    @Test fun `a network change races again`() {
        var network = "home"
        val reach = reach(HeldTimer()).apply { attach({ network }, { RouteMemory.EMPTY }, {}) }
        val shaken = Shaken()
        val handshake = shaken.by { }
        val client = { BridgeClient(machine(lan, relay), reach, handshake, answering(mutableListOf())) }

        client().hosts()
        reach.networkChanged()
        client().hosts()
        assertEquals("the same network keeps its winner", 1, shaken.routes.size)

        network = "cafe"
        reach.networkChanged()
        client().hosts()
        assertEquals("a new network is raced afresh", listOf(lan, lan), shaken.routes)
    }

    @Test fun `a route that failed on this network is dialled last, even after a restart`() {
        var saved = RouteMemory.EMPTY
        val first = reach(RouteDialer.timer(scheduler)).apply { attach({ "cafe" }, { RouteMemory.EMPTY }, { saved = it }) }
        val shaken = Shaken()
        val handshake = shaken.by { if (it == lan) throw ConnectException("unreachable") }

        BridgeClient(machine(lan, relay), first, handshake, answering(mutableListOf())).hosts()
        assertEquals(listOf(lan, relay), shaken.routes)
        val learned = saved.network("device-1", "cafe")
        assertEquals(relay, learned?.winner)
        assertEquals(listOf(lan), learned?.failed)

        // A fresh process: nothing cached, only the persisted memory.
        shaken.routes.clear()
        val restarted = reach(HeldTimer()).apply { attach({ "cafe" }, { saved }, { saved = it }) }
        val requests = mutableListOf<String>()
        BridgeClient(machine(lan, relay), restarted, handshake, answering(requests)).hosts()

        assertEquals("the remembered winner leads and the failed LAN never starts", listOf(relay), shaken.routes)
        assertEquals(listOf(relay.host), requests)
    }

    @Test fun `a pin mismatch aborts the race with the exception the link already handles`() {
        val shaken = Shaken()
        val handshake = shaken.by {
            if (it == lan) throw SSLHandshakeException("handshake").apply { initCause(PinMismatchException("wrong key")) }
        }
        val requests = mutableListOf<String>()
        val client = BridgeClient(machine(lan, relay), reach(RouteDialer.timer(scheduler)), handshake, answering(requests))

        try {
            client.hosts()
            fail("a pin mismatch must surface")
        } catch (e: IOException) {
            assertTrue("LinkRecovery and LiveLink match on PinMismatchException itself, got $e", e is PinMismatchException)
        }
        assertEquals("no other route is tried after a pin mismatch", listOf(lan), shaken.routes)
        assertTrue("nothing is sent to a machine with the wrong key", requests.isEmpty())
        assertNull(client.lastGoodRoute)
    }

    @Test fun `a winner that stops answering is dropped and the remaining routes are raced`() {
        val reach = reach(HeldTimer())
        val shaken = Shaken()
        val handshake = shaken.by { }
        var lanUp = true
        val requests = mutableListOf<String>()
        val open: (URL) -> HttpsURLConnection = { url ->
            requests += url.host
            if (url.host == lan.host && !lanUp) object : Connection(url) {
                override fun getResponseCode(): Int = throw IOException("connect timed out")
            } else Connection(url)
        }
        BridgeClient(machine(lan, relay), reach, handshake, open).hosts()
        lanUp = false
        BridgeClient(machine(lan, relay), reach, handshake, open).hosts()

        assertEquals(listOf(lan.host, lan.host, relay.host), requests)
        assertEquals(relay, reach.winner("device-1"))
    }

    @Test fun `forgetting a pairing drops its winner and its memory`() {
        var saved = RouteMemory.EMPTY
        val reach = reach(HeldTimer()).apply { attach({ "home" }, { RouteMemory.EMPTY }, { saved = it }) }
        BridgeClient(machine(lan, relay), reach, Shaken().by { }, answering(mutableListOf())).hosts()
        assertEquals(lan, saved.network("device-1", "home")?.winner)

        reach.forget("device-1")

        assertNull(reach.winner("device-1"))
        assertNull(saved.network("device-1", "home"))
    }

    @Test fun `a probe that wins during a request race is not overwritten by the race's worse route`() {
        val reach = reach(HeldTimer())
        val lanBlocked = CountDownLatch(1)
        val releaseLan = CountDownLatch(1)
        val lanDials = AtomicInteger()
        val handshake: Handshake = { route ->
            // The request's LAN dial hangs and then fails, so its race ends on the relay; the
            // probe's LAN dial, started meanwhile, answers at once.
            if (route == lan && lanDials.incrementAndGet() == 1) {
                lanBlocked.countDown()
                releaseLan.await(5, TimeUnit.SECONDS)
                throw ConnectException("LAN not up yet")
            }
            AutoCloseable {}
        }
        val request = Thread { BridgeClient(machine(lan, relay), reach, handshake, answering(mutableListOf())).hosts() }
        request.start()
        assertTrue(lanBlocked.await(5, TimeUnit.SECONDS))

        var probed: Route? = null
        val probe = Thread { probed = reach.probe("device-1", listOf(lan), handshake) }
        probe.start()
        waitFor { probe.state == Thread.State.BLOCKED || !probe.isAlive }
        releaseLan.countDown()
        request.join(5_000)
        probe.join(5_000)

        assertEquals(lan, probed)
        assertEquals("the better route the probe found must outlive the request race", lan, reach.winner("device-1"))
    }

    // ---- re-probe ---------------------------------------------------------------------

    @Test fun `an idle client and a stream on the best route schedule nothing`() {
        val timer = HeldTimer()
        val probes = AtomicInteger()
        val reprobe = StreamReprobe(timer, Runnable::run, { 1_000_000L }, { probes.incrementAndGet(); null }, { })

        ReprobePolicy.Trigger.entries.forEach(reprobe::trigger)
        reprobe.opened(lan, listOf(lan, relay))
        ReprobePolicy.Trigger.entries.forEach(reprobe::trigger)

        assertTrue("no timer without a stream on a worse route", timer.delays.isEmpty())
        assertEquals(0, probes.get())

        reprobe.opened(relay, listOf(lan, relay))
        assertEquals(listOf(ReprobePolicy.INTERVAL_MS), timer.delays)
        reprobe.closed()
        assertEquals("closing the stream disarms the tick", 1, timer.cancelled.get())
    }

    @Test fun `re-probe moves the stream to LAN when it answers again, opening the new stream before closing the old`() {
        val events = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val routes = listOf(lan, relay)
        val timer = HeldTimer()
        var now = 0L
        var dialTo = relay
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lanLive = CountDownLatch(1)
        lateinit var recovery: LinkRecovery
        lateinit var reprobe: StreamReprobe

        class Stream(private val route: Route) : LinkConnection {
            private val closed = CountDownLatch(1)
            override val lastGoodHost = route.host
            override val lastGoodRoute = route
            override fun fleet() = MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)
            override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
                events += "open:${route.kind}"
                onAlive()
                closed.await()
                throw IOException("closed")
            }
            override fun close() {
                if (closed.count > 0) events += "closed:${route.kind}"
                closed.countDown()
            }
        }

        recovery = LinkRecovery(
            scope = scope,
            connect = { Stream(dialTo) },
            onSnapshot = { _, _ -> },
            onAlive = { first, client ->
                if (first) {
                    events += "live:${client.lastGoodRoute?.kind}"
                    reprobe.opened(client.lastGoodRoute!!, routes)
                    if (client.lastGoodRoute == lan) lanLive.countDown()
                }
            },
            onFrame = { },
            onFailure = { failures += it },
        )
        reprobe = StreamReprobe(
            timer = timer,
            run = Runnable::run,
            now = { now },
            probe = { candidates ->
                assertEquals("only routes better than the relay are probed", listOf(lan), candidates)
                lan
            },
            move = { won ->
                dialTo = won
                recovery.move()
            },
        )
        try {
            recovery.restart()
            waitFor { timer.tasks.isNotEmpty() }
            assertEquals("on the relay, the 60 s tick is armed", listOf(ReprobePolicy.INTERVAL_MS), timer.delays)

            now = ReprobePolicy.INTERVAL_MS
            timer.tasks.first().run()

            assertTrue(lanLive.await(5, TimeUnit.SECONDS))
            waitFor { "closed:relay" in events }
            assertTrue(
                "the LAN stream must be open before the relay stream closes: $events",
                events.indexOf("open:lan") < events.indexOf("closed:relay"),
            )
            assertTrue("a move is not a failure: $failures", failures.isEmpty())
            assertEquals(
                "on LAN there is nothing better: every tick armed after the one that fired is cancelled",
                timer.delays.size - 1, timer.cancelled.get(),
            )
        } finally {
            recovery.stop()
            scope.cancel()
        }
    }

    @Test fun `a move whose stream never comes up leaves the old stream alone`() {
        val events = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relayLive = CountDownLatch(1)
        var calls = 0
        val recovery = LinkRecovery(
            scope = scope,
            connect = {
                calls++
                if (calls == 1) object : LinkConnection {
                    private val closed = CountDownLatch(1)
                    override val lastGoodHost = relay.host
                    override fun fleet() = MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)
                    override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
                        onAlive()
                        closed.await()
                    }
                    override fun close() {
                        events += "closed:relay"
                        closed.countDown()
                    }
                } else object : LinkConnection {
                    override val lastGoodHost = lan.host
                    override fun fleet() = error("a move does not refetch")
                    override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) =
                        throw IOException("LAN went away again")
                    override fun close() { events += "closed:lan" }
                }
            },
            onSnapshot = { _, _ -> },
            onAlive = { first, _ -> if (first) relayLive.countDown() },
            onFrame = { },
            onFailure = { events += "failure" },
        )
        try {
            recovery.restart()
            assertTrue(relayLive.await(5, TimeUnit.SECONDS))
            recovery.move()
            waitFor { "closed:lan" in events }
            Thread.sleep(100)
            assertEquals("the relay stream carries on, and nothing is reported", listOf("closed:lan"), events.toList())
        } finally {
            recovery.stop()
            scope.cancel()
        }
    }

    // ---- network fingerprint ----------------------------------------------------------

    @Test fun `the fingerprint tells networks apart and ignores what moves within one`() {
        val home = NetworkFingerprint.describe("wifi", "wlan0", listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))
        val homeAgain = NetworkFingerprint.describe("wifi", "wlan0", listOf("192.168.1.1"), listOf("192.168.1.0/24"), listOf("192.168.1.1"))
        val office = NetworkFingerprint.describe("wifi", "wlan0", listOf("10.0.0.2"), listOf("10.0.0.0/16"), listOf("10.0.0.1"))
        assertEquals(NetworkFingerprint.hash(home), NetworkFingerprint.hash(homeAgain))
        assertNotEquals(NetworkFingerprint.hash(home), NetworkFingerprint.hash(office))

        val cell = NetworkFingerprint.describe("cellular", "rmnet0", listOf("8.8.8.8"), listOf("10.1.2.0/24"), emptyList())
        val cellMoved = NetworkFingerprint.describe("cellular", "rmnet0", listOf("8.8.8.8"), listOf("10.9.9.0/24"), emptyList())
        assertEquals("a carrier's address churn is not a new network", cell, cellMoved)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting")
            Thread.sleep(10)
        }
    }
}
