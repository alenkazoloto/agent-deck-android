package dev.agentdeck.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.github.claudeagents.core.mobile.MobileBroadcast
import com.github.claudeagents.core.mobile.MobileEvent
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHosts
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.reach.ReprobePolicy
import com.github.claudeagents.core.reach.Route
import dev.agentdeck.companion.ambient.DeckWidget
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.HostReach
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.LinkRecovery
import dev.agentdeck.companion.data.LinkPolicy
import dev.agentdeck.companion.data.NetworkFingerprint
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PinMismatchException
import dev.agentdeck.companion.data.Reachability
import dev.agentdeck.companion.data.RouteReach
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.data.StreamReprobe
import dev.agentdeck.companion.notify.AttentionAlerts
import dev.agentdeck.companion.notify.DeckNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stale shows the aged cache; Offline means the phone has no network. Repair requires pairing again,
 * with no pin-mismatch override.
 */
sealed interface Link {
    data object Connecting : Link
    data object Live : Link
    data object Offline : Link
    data class Stale(val reason: String) : Link
    data class Repair(val reason: String) : Link
}

/** Record the address that actually answered, not just the advertised list, for Diagnostics (MP-07). */
data class LastGoodHost(val host: String, val atWallClockMs: Long, val reach: HostReach)

/**
 * One process-scoped socket serves screens, the optional background StreamService and notification sync.
 * Activity/view-model teardown must not own connectivity or delay network callbacks.
 */
class LiveLink private constructor(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SecureStore(app)

    private val _fleet = MutableStateFlow<MobileFleetSnapshot?>(null)
    val fleet: StateFlow<MobileFleetSnapshot?> = _fleet.asStateFlow()

    private val _link = MutableStateFlow<Link>(Link.Connecting)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Conversation keys the machine says have moved. The open transcript reloads off this. */
    private val _runs = MutableSharedFlow<Set<String>>(extraBufferCapacity = 32)
    val runs: SharedFlow<Set<String>> = _runs.asSharedFlow()

    /** Set when the user is looking at that conversation, so the phone does not tell them twice. */
    @Volatile
    var readingKey: String? = null

    @Volatile
    var machine: PairedMachine? = null
        private set
    private val machineLock = Any()

    @Volatile
    private var settings: AppSettings = store.settings()

    /** `key → trigger id` for everything currently on the shade. */
    private var notified: Map<String, String> = emptyMap()

    private val recovery: LinkRecovery = LinkRecovery(
        scope = scope,
        connect = { connectionForTest?.invoke() ?: client() },
        onSnapshot = { snapshot, client ->
            machine?.let { store.cacheSnapshot(it.id, snapshot) }
            rememberHost(client)
            failingSinceMs = null
            markAlive()
            _fleet.value = snapshot
            _link.value = Link.Live
            _refreshing.value = false
            syncNotifications(snapshot)
        },
        onAlive = { first, client ->
            markAlive()
            if (first) {
                rememberHost(client)
                machine?.let { m -> client.lastGoodRoute?.let { reprobe.opened(it, m.routes.map { r -> r.route }) } }
                onStreamProvedLive()
            } else if (_link.value.let { it is Link.Connecting || it is Link.Stale }) {
                // A banner guessed about the machine, disproved by a socket still delivering.
                failingSinceMs = null
                _link.value = Link.Live
            }
        },
        onFrame = ::onFrame,
        onFailure = {
            reprobe.closed()
            failLink(it)
        },
    )

    /** Moves the stream back to LAN or tailnet when it answers again; armed only while streaming. */
    private val reprobe: StreamReprobe = StreamReprobe(
        timer = RouteReach.shared.timer,
        run = { task -> scope.launch { task.run() } },
        now = SystemClock::elapsedRealtime,
        probe = { candidates ->
            machine?.let { RouteReach.shared.probe(it.id, candidates, RouteReach.tls(it.spkiFingerprint)) }
        },
        move = { recovery.move() },
    )

    /**
     * When the current run of failures began, on the monotonic clock — the grace window's
     * other end. Null whenever the link last succeeded, which is what makes the window a
     * property of *this* outage rather than of the process.
     */
    @Volatile
    private var failingSinceMs: Long? = null

    /** Use elapsedRealtime: doze/suspend gaps must not depend on wall-clock adjustments. */
    @Volatile
    private var lastAliveAtMs: Long = 0L

    /** Diagnostics needs the address carrying traffic, not merely every address that might work (MP-07). */
    private val _lastGood = MutableStateFlow<LastGoodHost?>(null)
    val lastGood: StateFlow<LastGoodHost?> = _lastGood.asStateFlow()

    /** The network the remembered host was learned on; `null` until one is observed. */
    @Volatile
    private var learnedOnNetwork: Long? = null

    init {
        RouteReach.shared.attach(
            network = { NetworkFingerprint.of(connectivity()) },
            load = store::routeMemory,
            save = store::saveRouteMemory,
        )
        registerNetworkCallback()
        observeForeground()
    }

    fun client(): BridgeClient? = machine?.let(::BridgeClient)

    /** Test seam: a JVM test has no socket to dial, and what the stream's frames do is the point. */
    internal var connectionForTest: (() -> LinkConnection?)? = null

    /**
     * Points the connection at a machine, or at nothing. Switching machines takes the shade
     * with it: its rows are about the machine being left. A row that outlives this (a push)
     * still acts on its own machine through [dev.agentdeck.companion.notify.Alert.machineId].
     */
    @Synchronized
    fun bind(machine: PairedMachine?) {
        if (this.machine == machine && machine != null) return
        recovery.stop()
        reprobe.closed()
        DeckNotifications.cancelAll(app, notified.keys)
        notified = emptyMap()
        synchronized(machineLock) { this.machine = machine }
        _fleet.value = machine?.let { store.cachedSnapshot(it.id) }
        _link.value = Link.Connecting
        _refreshing.value = false
        _lastGood.value = null
        learnedOnNetwork = null
        failingSinceMs = null
        lastAliveAtMs = 0L
        if (machine != null) refresh(initial = true)
    }

    /** Re-read after the Settings screen writes; the triggers decide what the next frame posts. */
    fun settingsChanged(settings: AppSettings) {
        this.settings = settings
        // Turning a trigger off must take its rows off the shade now, not at the next frame.
        val plan = AttentionAlerts.plan(_fleet.value, settings, notified, readingKey, machine?.id)
        notified = plan.seen
        DeckNotifications.apply(app, plan.copy(post = emptyList()))
    }

    @Synchronized
    fun refresh(initial: Boolean = false) {
        if (machine == null) return
        _refreshing.value = !initial
        if (initial) _link.value = Link.Connecting
        reprobe.closed()
        recovery.restart()
    }

    /** A network handover or foreground return immediately replaces the old connection. */
    @Synchronized
    fun wake() {
        // A changed certificate or revoked pairing cannot heal through transport retries.
        if (_link.value is Link.Repair) return
        refresh()
    }

    /** A new default network: a live stream moves make-before-break; anything else reconnects now. */
    @Synchronized
    internal fun handover() {
        if (machine == null) return
        if (_link.value is Link.Repair) {
            recovery.stop()
            return
        }
        if (!recovery.moveIfLive()) refresh()
    }

    /** The socket delivered a line. Monotonic, because the gap may span a doze. */
    private fun markAlive() {
        lastAliveAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * A reconnect is live again. The list heals itself off the `fleet` frame the machine sends
     * on connect; an open transcript has nothing equivalent, so it is told to refetch here.
     */
    private fun onStreamProvedLive() {
        failingSinceMs = null
        _link.value = Link.Live
        readingKey?.let { _runs.tryEmit(setOf(it)) }
    }

    private fun onFrame(frame: BridgeClient.SseFrame) {
        when (frame.event) {
            MobileEvent.FLEET -> {
                val snapshot = MobileProtocol.parseObject(frame.data)
                    ?.let(MobileFleetSnapshot::fromJson) ?: return
                machine?.let { store.cacheSnapshot(it.id, snapshot) }
                _fleet.value = snapshot
                _link.value = Link.Live
                syncNotifications(snapshot)
            }
            MobileEvent.RUN -> {
                // A malformed body is treated as an unnamed ping rather than dropped — the
                // frame's arrival is itself evidence that something moved.
                val keys = MobileProtocol.parseObject(frame.data)
                    ?.let(MobileBroadcast::runKeys).orEmpty()
                _runs.tryEmit(keys)
            }
            MobileEvent.BYE ->
                _link.value = Link.Stale("The IDE closed the connection.")
            // `usage` carries the strip line, which rides along on the next fleet snapshot.
        }
    }

    private fun syncNotifications(snapshot: MobileFleetSnapshot) {
        val plan = AttentionAlerts.plan(snapshot, settings, notified, readingKey, machine?.id)
        notified = plan.seen
        if (!plan.isEmpty) DeckNotifications.apply(app, plan)
        // The widget's own tick is half-hourly; this is what makes it agree with the app.
        DeckWidget.refresh(app)
    }

    /** Everything the shade is claiming right now — the ongoing row's subtitle reads this. */
    fun waitingSummary(): String? = AttentionAlerts.summary(notified)

    private fun rememberHost(client: LinkConnection) {
        val host = client.lastGoodHost ?: return
        val current = machine ?: return
        learnedOnNetwork = currentNetworkHandle()
        val route = client.lastGoodRoute ?: current.routeOn(host)
        // A dial begun before the user removed this route can still answer on it.
        if (route != null) updateMachine { m ->
            m.takeIf { it.id == current.id && it.preferredRoute != route && it.routes.any { r -> r.route == route } }
                ?.copy(preferredRoute = route)
        }
        _lastGood.value = LastGoodHost(host, System.currentTimeMillis(), Reachability.of(host))
    }

    /**
     * Every read-modify-write of [machine]. [rememberHost] runs under [LinkRecovery]'s lock, which
     * [bind] takes under this object's, so this lock is a separate leaf that takes no other lock.
     */
    private fun updateMachine(change: (PairedMachine) -> PairedMachine?): PairedMachine? = synchronized(machineLock) {
        val current = machine ?: return null
        val updated = change(current)?.takeIf { it != current } ?: return null
        machine = updated
        store.save(updated)
        updated
    }

    /**
     * Takes the routes the machine says it answers on now (`GET /v1/hosts`), so a pairing
     * made before the relay or a tailnet existed can reach it from another network. Returns the
     * machine now held, or null when nothing changed.
     */
    @Synchronized
    fun adoptHosts(advertised: MobileHosts): PairedMachine? = updateMachine { it.learning(advertised) }

    /** The user removed a route the pairing link named. Returns the machine now held, or null when refused. */
    @Synchronized
    fun removePairedRoute(route: Route): PairedMachine? = updateMachine { it.withoutPairedRoute(route) }

    /** Forget cached LAN success only on a changed network; repeated onAvailable must preserve it (MP-04). */
    private fun forgetHostIfNetworkChanged(network: Network) {
        if (machine == null) return
        if (learnedOnNetwork == network.networkHandle) return
        learnedOnNetwork = null
        updateMachine { m -> m.forgettingNetworkScopedRoute().takeIf { it.preferredRoute != m.preferredRoute } } ?: return
        _lastGood.value = null
    }

    private fun currentNetworkHandle(): Long? =
        runCatching { connectivity()?.activeNetwork?.networkHandle }.getOrNull()

    /** Observe process foregrounding: the link outlives Activities, and a frozen process cannot notice socket death. */
    private fun observeForeground() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (machine == null) return
                    val silentFor = SystemClock.elapsedRealtime() - lastAliveAtMs
                    if (LinkPolicy.wakesOnForeground(silentFor)) wake()
                    else reprobe.trigger(ReprobePolicy.Trigger.FOREGROUND)
                }
            })
        }.onFailure { Log.w(TAG, "No process lifecycle observer; foreground will not wake the link", it) }
    }

    /** Show known pin/revocation/server refusals immediately; delay an unattributed guess a retry may disprove. */
    fun failLink(error: Throwable) {
        val pin = error as? PinMismatchException
        val refusal = error as? BridgeRefusal
        _refreshing.value = false
        when {
            pin != null -> _link.value = Link.Repair(pin.message.orEmpty())
            refusal != null && refusal.isRevoked -> _link.value = Link.Repair(refusal.message)
            refusal != null -> _link.value = Link.Stale(refusal.message)
            // The radio is down. Nothing about the machine is knowable, and saying it is not
            // answering blames the wrong end.
            !online() -> _link.value = Link.Offline
            else -> {
                val now = SystemClock.elapsedRealtime()
                val since = failingSinceMs ?: now.also { failingSinceMs = it }
                if (LinkPolicy.isTransportSilence(error)) {
                    // The watchdog doing its job, not news. Reconnecting is the whole response.
                    Log.i(TAG, "Stream went quiet; reconnecting")
                } else {
                    Log.i(TAG, "Bridge unreachable", error)
                }
                _link.value = if (LinkPolicy.announces(since, now)) {
                    Link.Stale(LinkPolicy.stalenessSentence(machine?.machineName.orEmpty(), machine?.addresses().orEmpty()))
                } else {
                    Link.Connecting
                }
            }
        }
    }

    // ---- the network itself -----------------------------------------------------------

    private fun connectivity(): ConnectivityManager? =
        app.getSystemService(ConnectivityManager::class.java)

    private fun online(): Boolean = connectivity()?.activeNetwork != null

    /** The network callback shares the stream's process lifetime. Registration failure falls back to retry backoff. */
    private fun registerNetworkCallback() {
        val manager = connectivity() ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(this@LiveLink) {
                        // Before the machine check: requests from screens race again on the new network too.
                        RouteReach.shared.networkChanged()
                        if (machine == null) return
                        // The move or restart below races every route afresh, so it is the network-change re-probe.
                        reprobe.closed()
                        forgetHostIfNetworkChanged(network)
                        handover()
                    }
                }

                override fun onLost(network: Network) {
                    if (machine != null && !online()) _link.value = Link.Offline
                }
            })
        }.onFailure { Log.w(TAG, "No network callback; falling back to the reconnect backoff", it) }
    }

    companion object {
        private const val TAG = "AgentDeck"

        @Volatile
        private var instance: LiveLink? = null

        /** Scope the singleton to Application: Robolectric replaces it/files per test, so a stale instance leaks caches. */
        fun of(context: Context): LiveLink {
            val app = context.applicationContext
            instance?.takeIf { it.app === app }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.app === app } ?: LiveLink(app).also {
                    instance?.recovery?.stop()
                    instance = it
                }
            }
        }
    }
}
