package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.HostReach
import dev.agentdeck.companion.data.LinkPolicy
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.Reachability
import dev.agentdeck.companion.data.SseReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Wave 1 of `PLAN-MOBILE-POLISH.md` — the four supervisions the connection did not have.
 *
 * The owner's complaint was "I see 'This phone is offline' and 'No connection to IDE' too
 * often", and the audit's verdict was that the link is not unreliable but *unsupervised*.
 * Every assertion here fails against the commit before that plan: three of them because the
 * seam did not exist, and the rest because the value was the opposite one.
 */
class LinkSupervisionTest {

    // ---- MP-02: the stream could not tell a live socket from a dead one ------------------

    /**
     * The defect, stated as an assertion: the stream's read deadline was `0`, which is
     * `HttpsURLConnection` for "wait forever". A half-open socket then blocked in `readLine()`
     * with no exception, so the reconnect loop never ran and the app stayed on `Live`.
     */
    @Test
    fun `the stream carries a finite read deadline`() {
        assertTrue(
            "a stream with no read deadline blocks in readLine() forever on a half-open socket",
            LinkPolicy.STREAM_READ_TIMEOUT_MS > 0,
        )
    }

    /**
     * MP-02's stated trap. The two deadlines answer different questions — a stream is silent
     * by design between events, a request has a response coming — and collapsing them breaks
     * one or the other. This is the check that notices someone "tidying" them into one constant.
     */
    @Test
    fun `the stream's deadline is the heartbeat's multiple, and is not the request deadline`() {
        assertEquals(
            "the phone's watchdog must be a multiple of the period the machine writes at",
            MobileProtocol.Stream.HEARTBEAT_MS * MobileProtocol.Stream.MISSED_HEARTBEATS_BEFORE_DEAD,
            LinkPolicy.STREAM_READ_TIMEOUT_MS.toLong(),
        )
        assertNotEquals(
            "an ordinary request must not inherit the stream's patience",
            LinkPolicy.STREAM_READ_TIMEOUT_MS,
            LinkPolicy.REQUEST_READ_TIMEOUT_MS,
        )
        assertTrue(
            "a healthy stream must survive at least two missed keep-alives",
            LinkPolicy.STREAM_READ_TIMEOUT_MS > 2 * MobileProtocol.Stream.HEARTBEAT_MS,
        )
    }

    /** The watchdog firing is the supervision working, not news to show a user. */
    @Test
    fun `a socket timeout is transport silence, and a refusal is not`() {
        assertTrue(LinkPolicy.isTransportSilence(SocketTimeoutException("read timed out")))
        assertFalse(LinkPolicy.isTransportSilence(IllegalStateException("boom")))
        assertFalse(LinkPolicy.isTransportSilence(null))
    }

    /**
     * The other half of MP-02, and the reason [SseReader] was lifted out of the socket: an
     * idle machine sends no frames for minutes, so a caller watching frames alone cannot tell
     * "connected and quiet" from "frozen". The keep-alive comment is the only proof of life,
     * and by the SSE spec it is not a frame.
     */
    @Test
    fun `a keep-alive comment proves life without dispatching a frame`() {
        var alive = 0
        val frames = mutableListOf<BridgeClient.SseFrame>()
        SseReader.read(": keep-alive\n\n".reader().buffered(), { alive++ }) { frames += it; true }

        assertTrue("the keep-alive line must count as proof of life", alive > 0)
        assertTrue("a comment is not an event and must not be dispatched", frames.isEmpty())
    }

    @Test
    fun `a real event still arrives, with its data joined`() {
        val frames = mutableListOf<BridgeClient.SseFrame>()
        SseReader.read(
            "id: 7\nevent: fleet\ndata: {\"a\":1}\ndata: tail\n\n".reader().buffered(),
            {},
        ) { frames += it; true }

        assertEquals(1, frames.size)
        assertEquals("fleet", frames[0].event)
        assertEquals("7", frames[0].id)
        assertEquals("{\"a\":1}\ntail", frames[0].data)
    }

    /** `onFrame` returning false is how the caller cancels; the reader must stop reading. */
    @Test
    fun `the reader stops when the caller says stop`() {
        val frames = mutableListOf<BridgeClient.SseFrame>()
        SseReader.read(
            "event: a\ndata: 1\n\nevent: b\ndata: 2\n\n".reader().buffered(),
            {},
        ) { frames += it; false }

        assertEquals("the second frame must not be read after a refusal", 1, frames.size)
    }

    // ---- MP-03: coming back to the app woke nothing --------------------------------------

    /**
     * The gate is silence, not elapsed time, so this cannot become a poll — inside the
     * watchdog window the stream has demonstrably been writing.
     */
    @Test
    fun `the foreground wakes the link only after the stream has gone quiet`() {
        assertFalse(LinkPolicy.wakesOnForeground(0))
        assertFalse(LinkPolicy.wakesOnForeground(MobileProtocol.Stream.HEARTBEAT_MS))
        assertTrue(LinkPolicy.wakesOnForeground(MobileProtocol.Stream.CLIENT_WATCHDOG_MS))
        assertTrue(LinkPolicy.wakesOnForeground(60 * 60 * 1000L))
    }

    // ---- MP-04: the remembered address outlived the network it belonged to ----------------

    private fun machine(vararg hosts: String, preferred: String?) = PairedMachine(
        machineName = "workshop",
        hosts = hosts.toList(),
        port = 8443,
        spkiFingerprint = "ab",
        token = "t",
        deviceId = "d",
        preferredHost = preferred,
    )

    /**
     * The whole symptom: `dialOrder()` puts the remembered host first, always. After one
     * success at the desk that was `192.168.1.24` forever, so every request and every stream
     * reconnect on cellular paid a five-second connect timeout before falling through.
     */
    @Test
    fun `a new network forgets a LAN address and the next dial is not it`() {
        val atDesk = machine("bore.pub", "192.168.1.24", preferred = "192.168.1.24")
        assertEquals("192.168.1.24", atDesk.dialOrder().first())

        val elsewhere = atDesk.forgettingNetworkScopedHost()
        assertNull(elsewhere.preferredHost)
        assertNotEquals(
            "the second network must not lead with the first network's address",
            "192.168.1.24",
            elsewhere.dialOrder().first(),
        )
    }

    /**
     * The half that would be a regression if the fix were "forget on every network change":
     * an overlay or relay address is exactly the one that still works, and dropping it would
     * throw away the only reachable host.
     */
    @Test
    fun `a relay or overlay address survives a network change`() {
        assertEquals(
            "bore.pub",
            machine("bore.pub", "10.0.0.4", preferred = "bore.pub")
                .forgettingNetworkScopedHost().preferredHost,
        )
        assertEquals(
            "100.101.102.103",
            machine("100.101.102.103", preferred = "100.101.102.103")
                .forgettingNetworkScopedHost().preferredHost,
        )
    }

    @Test
    fun `addresses are classified by whether they mean anything off this network`() {
        assertEquals(HostReach.LAN, Reachability.of("192.168.1.24"))
        assertEquals(HostReach.LAN, Reachability.of("10.0.0.4"))
        assertEquals(HostReach.LAN, Reachability.of("172.20.1.1"))
        assertEquals(HostReach.LAN, Reachability.of("169.254.3.9"))
        assertEquals(HostReach.LAN, Reachability.of("workshop.local"))
        assertEquals(HostReach.LOOPBACK, Reachability.of("127.0.0.1"))
        assertEquals(HostReach.PUBLIC, Reachability.of("bore.pub"))
        assertEquals(HostReach.PUBLIC, Reachability.of("203.0.113.7"))
        // 172.32 is outside 172.16/12 and is ordinary public space — the off-by-one that a
        // hand-written private-range check gets wrong.
        assertEquals(HostReach.PUBLIC, Reachability.of("172.32.0.1"))
        // Tailscale's range. Private by RFC 6598 and reachable from anywhere on the tailnet,
        // which is why this enum has three cases rather than two.
        assertEquals(HostReach.OVERLAY, Reachability.of("100.101.102.103"))
        assertEquals(HostReach.OVERLAY, Reachability.of("fd7a:115c::1"))
    }

    // ---- MP-05: every blip was announced as a verdict -------------------------------------

    /**
     * The banner the owner is tired of reading. `failLink` wrote it on *every* iteration of the
     * reconnect loop, and the first rung is one second — so an ordinary handover painted an
     * accusation about a healthy machine and withdrew it before it could be read.
     */
    @Test
    fun `a verdict must outlast the retry that would have taken it back`() {
        assertFalse("the first failure is not a verdict", LinkPolicy.announces(null, 0))
        assertFalse(LinkPolicy.announces(0L, LinkPolicy.GRACE_MS - 1))
        assertTrue(LinkPolicy.announces(0L, LinkPolicy.GRACE_MS))
        assertTrue(
            "the window must outlast a full dial budget, or one timed-out dial is a verdict",
            LinkPolicy.GRACE_MS > LinkPolicy.CONNECT_TIMEOUT_MS,
        )
    }

    /** Several reconnects — one phone across an outage, or several behind one router. */
    @Test
    fun `the ladder is jittered, bounded, and still climbs`() {
        val rung = LinkPolicy.FIRST_BACKOFF_MS
        assertNotEquals(
            "two rungs drawn from opposite ends of the jitter must differ",
            LinkPolicy.nextBackoffMs(rung, 0.0),
            LinkPolicy.nextBackoffMs(rung, 1.0),
        )
        for (jitter in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val next = LinkPolicy.nextBackoffMs(rung, jitter)
            val doubled = rung * 2
            assertTrue(
                "jitter must stay inside ±${LinkPolicy.JITTER} of the rung, got $next",
                next >= (doubled * (1 - LinkPolicy.JITTER)).toLong() &&
                    next <= (doubled * (1 + LinkPolicy.JITTER)).toLong(),
            )
            assertTrue("a rung may never fall below the first", next >= LinkPolicy.FIRST_BACKOFF_MS)
        }
        val ceiling = LinkPolicy.nextBackoffMs(LinkPolicy.MAX_BACKOFF_MS, 1.0)
        assertTrue(
            "the ceiling may be jittered but not exceeded by more than the jitter",
            ceiling <= (LinkPolicy.MAX_BACKOFF_MS * (1 + LinkPolicy.JITTER)).toLong(),
        )
    }

    /**
     * MP-05 part 3, and MP-07 part 1. The app had one sentence for every non-refusal failure
     * and said it about IDEs that were running perfectly, to users on a network the phone was
     * never going to reach.
     */
    @Test
    fun `the sentence names the network when no advertised address leaves it`() {
        val lanOnly = LinkPolicy.stalenessSentence("workshop", listOf("192.168.1.24", "workshop.local"))
        assertTrue("$lanOnly must name the machine", lanOnly.contains("workshop"))
        assertTrue("$lanOnly must name the network", lanOnly.contains("network"))
        assertFalse(
            "blaming the IDE for a routing problem is the defect",
            lanOnly.contains("has to be running"),
        )

        val reachable = LinkPolicy.stalenessSentence("workshop", listOf("bore.pub", "192.168.1.24"))
        assertTrue(reachable.contains("has to be running"))
    }

    @Test
    fun `a machine with any address that travels is not lan-only`() {
        assertTrue(Reachability.isLanOnly(listOf("192.168.1.24")))
        assertFalse(Reachability.isLanOnly(listOf("192.168.1.24", "bore.pub")))
        assertFalse(Reachability.isLanOnly(listOf("100.101.102.103")))
        // No addresses at all is not a claim about routing; it is a broken pairing.
        assertFalse(Reachability.isLanOnly(emptyList()))
    }
}
