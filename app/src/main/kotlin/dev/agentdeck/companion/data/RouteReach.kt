package dev.agentdeck.companion.data

import com.github.claudeagents.core.reach.Route
import com.github.claudeagents.core.reach.RouteDialer
import com.github.claudeagents.core.reach.RouteMemory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** A completed pinned TLS handshake on one route; closing it ends the probe socket. */
typealias Handshake = (Route) -> AutoCloseable

/**
 * The phone's one dialing policy, shared by every [BridgeClient] in the process: the app builds a
 * client per call, so a winner kept on the client would be raced for again on every request.
 *
 * A race ([RouteDialer]) only obtains a handshaken route; the request then runs on the winner,
 * so a refusal or an uncertain delivery is never turned into "try the next route". The winner
 * is kept per pairing and network fingerprint until it fails or the network changes, and what
 * each race taught is kept in [RouteMemory] (winner first, routes that failed here last).
 */
class RouteReach internal constructor(
    executor: Executor,
    val timer: RouteDialer.Timer,
    staggerMs: Long = RouteDialer.STAGGER_MS,
) {
    private data class Key(val pairingId: String, val network: String)

    private val dialer = RouteDialer<AutoCloseable>(
        executor, timer, isFatal = { Pinning.pinFailure(it) != null }, close = { it.close() }, staggerMs,
    )
    private val winners = ConcurrentHashMap<Key, Route>()
    private val raceLocks = ConcurrentHashMap<Key, Any>()

    @Volatile private var network: () -> String? = { null }
    @Volatile private var save: (RouteMemory) -> Unit = {}
    @Volatile private var lastNetwork: String? = null

    @Volatile
    var memory: RouteMemory = RouteMemory.EMPTY
        private set

    /** Where the network fingerprint comes from and where memory lives; [LiveLink] calls it once per process. */
    fun attach(network: () -> String?, load: () -> RouteMemory, save: (RouteMemory) -> Unit) {
        this.network = network
        this.save = save
        memory = load()
        lastNetwork = currentNetwork()
    }

    fun currentNetwork(): String? = runCatching { network() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * A new default network. Winners are keyed by fingerprint, so a changed one already misses;
     * clearing also covers an unknown network and a same-fingerprint handover whose old socket died.
     */
    fun networkChanged() {
        val now = currentNetwork()
        if (now == null || now != lastNetwork) winners.clear()
        lastNetwork = now
    }

    /** The route requests for [pairingId] use on this network, or null until a race has won here. */
    fun winner(pairingId: String): Route? = winners[Key(pairingId, currentNetwork().orEmpty())]

    fun forget(pairingId: String) {
        raceLocks.keys.removeIf { it.pairingId == pairingId }
        winners.keys.removeIf { it.pairingId == pairingId }
        update { it.forget(pairingId) }
    }

    /**
     * Runs [attempt] on the winning route for [pairingId] on this network, racing first when none
     * has won here. A transport failure before the machine answered drops that winner and races
     * the routes not yet tried in this call; a pin mismatch, refusal or uncertain delivery ends it.
     * [remembered] stands in for the cache when there is no pairing (pairing flow, tests).
     */
    internal fun <T> request(
        pairingId: String?,
        routes: List<Route>,
        handshake: Handshake,
        remembered: Route?,
        checkOpen: () -> Unit,
        retry: (Exception) -> Boolean,
        answered: (Route) -> Unit,
        attempt: (Route) -> T,
    ): T {
        val fingerprint = currentNetwork()
        val key = pairingId?.let { Key(it, fingerprint.orEmpty()) }
        val tried = HashSet<Route>()
        var last: Exception? = null
        while (true) {
            checkOpen()
            val left = routes.filter { it !in tried }
            if (left.isEmpty()) throw last ?: IOException("No address was configured for this machine.")
            val route = if (key == null) {
                remembered?.takeIf { it in left } ?: race(null, fingerprint, left, handshake)
            } else {
                winnerOrRace(key, fingerprint, left, handshake)
            }
            checkOpen()
            tried += route
            try {
                return attempt(route).also { answered(route) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (uncertain: BridgeDeliveryUncertain) {
                throw uncertain
            } catch (refusal: BridgeRefusal) {
                answered(route)
                throw refusal
            } catch (error: Exception) {
                checkOpen()
                Pinning.pinFailure(error)?.let { throw it }
                if (key != null) winners.remove(key, route)
                if (!retry(error)) throw error
                if (pairingId != null) update { it.recordFailure(pairingId, fingerprint, listOf(route)) }
                last = error
            }
        }
    }

    /**
     * Races only [candidates] — routes ranked better than the one a stream is on — and, when one
     * wins, makes it the winner the next connection for [pairingId] uses. Null when none answered.
     */
    fun probe(pairingId: String, candidates: List<Route>, handshake: Handshake): Route? {
        if (candidates.isEmpty()) return null
        val fingerprint = currentNetwork()
        val won = runCatching { race(pairingId, fingerprint, candidates, handshake) }.getOrNull() ?: return null
        val key = Key(pairingId, fingerprint.orEmpty())
        // Under the race lock, so a request race finishing now cannot put the worse route back.
        synchronized(raceLocks.computeIfAbsent(key) { Any() }) { winners[key] = won }
        return won
    }

    private fun winnerOrRace(key: Key, fingerprint: String?, routes: List<Route>, handshake: Handshake): Route {
        winners[key]?.takeIf { it in routes }?.let { return it }
        // One race per pairing and network: the requests a screen fires at once wait for it.
        synchronized(raceLocks.computeIfAbsent(key) { Any() }) {
            winners[key]?.takeIf { it in routes }?.let { return it }
            return race(key.pairingId, fingerprint, routes, handshake).also { winners[key] = it }
        }
    }

    private fun race(pairingId: String?, fingerprint: String?, routes: List<Route>, handshake: Handshake): Route {
        val order = pairingId?.let { memory.order(it, fingerprint, routes) } ?: Route.ordered(routes)
        val dialed = try {
            dialer.dial(order, handshake)
        } catch (all: RouteDialer.AllRoutesFailed) {
            if (pairingId != null) update { it.recordFailure(pairingId, fingerprint, all.causes.map { c -> c.route }) }
            // The last route's own exception, as the walk threw, so the link copy classifies it the same way.
            throw all.causes.lastOrNull()?.cause ?: all
        } catch (error: Throwable) {
            throw Pinning.pinFailure(error) ?: error
        }
        runCatching { dialed.connection.close() }
        if (pairingId != null) {
            update { it.recordWin(pairingId, fingerprint, dialed.route, dialed.failed.map { f -> f.route }) }
        }
        return dialed.route
    }

    private fun update(change: (RouteMemory) -> RouteMemory) {
        synchronized(this) {
            val next = change(memory)
            if (next == memory) return
            memory = next
            runCatching { save(next) }
        }
    }

    companion object {
        /** Above any machine's route count; a rejected start counts as that route failing. */
        private const val MAX_DIALS = 16
        private const val IDLE_SECONDS = 30L

        private fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

        /** Threads exist only while dialing: an idle phone holds none. */
        val shared: RouteReach by lazy {
            val executor = ThreadPoolExecutor(0, MAX_DIALS, IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue(), daemon("route-dial"))
            val scheduler = ScheduledThreadPoolExecutor(1, daemon("route-timer")).apply {
                setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS)
                allowCoreThreadTimeOut(true)
                removeOnCancelPolicy = true
            }
            RouteReach(executor, RouteDialer.timer(scheduler))
        }

        /** TCP connect and the pinned TLS handshake, each under the connect timeout; nothing is sent. */
        fun tls(spkiFingerprint: String): Handshake = { route ->
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(route.host, route.port), LinkPolicy.CONNECT_TIMEOUT_MS)
                raw.soTimeout = LinkPolicy.CONNECT_TIMEOUT_MS
                val tls = Pinning.socketFactory(spkiFingerprint).createSocket(raw, route.host, route.port, true) as SSLSocket
                tls.soTimeout = LinkPolicy.CONNECT_TIMEOUT_MS
                tls.startHandshake()
                tls
            } catch (error: Throwable) {
                runCatching { raw.close() }
                throw error
            }
        }

        /** A fake transport has no socket to shake hands with; its request is the whole test. */
        val NO_HANDSHAKE: Handshake = { AutoCloseable {} }
    }
}
