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
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.ambient.DeckWidget
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.HostReach
import dev.agentdeck.companion.data.LinkPolicy
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PinMismatchException
import dev.agentdeck.companion.data.Reachability
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.notify.AttentionAlerts
import dev.agentdeck.companion.notify.DeckNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * How the app currently stands with the machine.
 *
 * [Stale] is a first-class state, not an error banner: when the IDE is not running there is
 * nothing to connect to, and the honest thing to show is the last snapshot with its age.
 * [Offline] is the same shape with a different owner — this phone has no network at all, and
 * saying "the machine is not answering" about a phone in a tunnel blames the wrong end.
 * [Repair] is terminal until the user pairs again: a pin mismatch has no override.
 */
sealed interface Link {
    data object Connecting : Link
    data object Live : Link
    data object Offline : Link
    data class Stale(val reason: String) : Link
    data class Repair(val reason: String) : Link
}

/**
 * Which of a machine's addresses actually carried the last successful call, and when.
 *
 * Settings › Diagnostics reported `Connection: Connected` and the machine's whole address
 * list, which is every address that *might* work and none that did — the single line that
 * turns "it says it cannot reach my machine" into "it is dialling the LAN address from the
 * train" (MP-07).
 */
data class LastGoodHost(val host: String, val atWallClockMs: Long, val reach: HostReach)

/**
 * The connection, owned by the process rather than by a screen.
 *
 * It used to live in `viewModelScope`, which made "am I connected" a property of whether an
 * Activity existed: backing out of the app tore the stream down with the view model, and a
 * network change waited out a backoff that had climbed to 30 s instead of reconnecting on the
 * callback that already knew. Both are lifecycle questions, and a view model is not where a
 * lifecycle lives.
 *
 * So: one instance per process, one socket, three readers — [DeckViewModel] for the screens,
 * [dev.agentdeck.companion.service.StreamService] when the user has asked to stay connected
 * while backgrounded, and the notification sync below, which is the only reason any of this
 * runs when nobody is looking.
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

    @Volatile
    private var settings: AppSettings = store.settings()

    /** `key → trigger id` for everything currently on the shade. */
    private var notified: Map<String, String> = emptyMap()

    private var streamJob: Job? = null

    /**
     * When the current run of failures began, on the monotonic clock — the grace window's
     * other end. Null whenever the link last succeeded, which is what makes the window a
     * property of *this* outage rather than of the process.
     */
    @Volatile
    private var failingSinceMs: Long? = null

    /**
     * The last time the socket delivered anything at all, keep-alives included.
     *
     * `elapsedRealtime` rather than wall clock: the interesting gaps here are dozes and
     * suspends, which a wall clock can step over in either direction.
     */
    @Volatile
    private var lastAliveAtMs: Long = 0L

    /**
     * Which address answered last, and when — the line Settings › Diagnostics needs to turn a
     * support question into a self-diagnosis (MP-07). It reports `Connection: Connected` and
     * the machine's whole address list today, and never which of them is actually carrying the
     * traffic.
     */
    private val _lastGood = MutableStateFlow<LastGoodHost?>(null)
    val lastGood: StateFlow<LastGoodHost?> = _lastGood.asStateFlow()

    /** The network the remembered host was learned on; `null` until one is observed. */
    @Volatile
    private var learnedOnNetwork: Long? = null

    init {
        registerNetworkCallback()
        observeForeground()
    }

    fun client(): BridgeClient? = machine?.let(::BridgeClient)

    /**
     * Points the connection at a machine, or at nothing. Switching machines takes the shade
     * with it — a notification about the laptop, tapped while the phone is showing the
     * desktop, would open a conversation that is not in the list.
     */
    fun bind(machine: PairedMachine?) {
        if (this.machine?.id == machine?.id && machine != null) {
            this.machine = machine
            return
        }
        streamJob?.cancel()
        streamJob = null
        DeckNotifications.cancelAll(app, notified.keys)
        notified = emptyMap()
        this.machine = machine
        _fleet.value = machine?.let { store.cachedSnapshot(it.id) }
        _link.value = if (machine == null) Link.Connecting else Link.Connecting
        if (machine != null) refresh(initial = true)
    }

    /** Re-read after the Settings screen writes; the triggers decide what the next frame posts. */
    fun settingsChanged(settings: AppSettings) {
        this.settings = settings
        // Turning a trigger off must take its rows off the shade now, not at the next frame.
        val plan = AttentionAlerts.plan(_fleet.value, settings, notified, readingKey)
        notified = plan.seen
        DeckNotifications.apply(app, plan.copy(post = emptyList()))
    }

    fun refresh(initial: Boolean = false) {
        val client = client() ?: return
        _refreshing.value = !initial
        if (initial) _link.value = Link.Connecting
        scope.launch {
            runCatching { client.fleet() }
                .onSuccess { snapshot ->
                    machine?.let { store.cacheSnapshot(it.id, snapshot) }
                    rememberHost(client)
                    failingSinceMs = null
                    markAlive()
                    _fleet.value = snapshot
                    _link.value = Link.Live
                    _refreshing.value = false
                    syncNotifications(snapshot)
                    startStream()
                }
                .onFailure(::failLink)
        }
    }

    /**
     * Reconnect **now**, discarding whatever backoff had accumulated.
     *
     * This is what a network-available callback is for. Walking from Wi-Fi to cellular used to
     * cost up to 30 s of sleeping on a ladder climbed while the old network was dying, on a
     * phone that already knew a new one had arrived.
     */
    fun wake() {
        streamJob?.cancel()
        streamJob = null
        refresh()
    }

    /**
     * The SSE stream, supervised at both ends.
     *
     * Three things here are load-bearing and were all missing:
     *
     * - the socket now carries a read deadline ([LinkPolicy.STREAM_READ_TIMEOUT_MS]), so a
     *   half-open connection surfaces as a [java.net.SocketTimeoutException] instead of
     *   blocking in `readLine()` forever with the app still saying "live" (MP-02);
     * - a failure no longer paints a verdict on its first frame. It sets [failingSinceMs] and
     *   the banner stays on the honest word — "Connecting…" — until the ladder has failed for
     *   longer than [LinkPolicy.GRACE_MS] (MP-05);
     * - a reconnect that proves live reloads the open conversation, because there is no
     *   `Last-Event-ID` replay and never was (MP-06).
     */
    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            var backoffMs = LinkPolicy.FIRST_BACKOFF_MS
            while (isActive) {
                val client = client() ?: return@launch
                // Per attempt: the first line off a reconnected socket is what tells the open
                // transcript to refetch. Frames alone would not — an idle machine sends none.
                var proved = false
                val outcome = runCatching {
                    client.stream(
                        onAlive = {
                            markAlive()
                            if (!proved) {
                                proved = true
                                backoffMs = LinkPolicy.FIRST_BACKOFF_MS
                                onStreamProvedLive()
                            }
                        },
                    ) { frame ->
                        onFrame(frame)
                        isActive
                    }
                }
                val fatal = outcome.exceptionOrNull()?.let { error ->
                    failLink(error)
                    error is PinMismatchException || (error as? BridgeRefusal)?.isRevoked == true
                } ?: false
                if (fatal || !isActive) return@launch
                delay(backoffMs)
                backoffMs = LinkPolicy.nextBackoffMs(backoffMs, Random.nextDouble())
            }
        }
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
        val plan = AttentionAlerts.plan(snapshot, settings, notified, readingKey)
        notified = plan.seen
        if (!plan.isEmpty) DeckNotifications.apply(app, plan)
        // The widget's own tick is half-hourly; this is what makes it agree with the app.
        DeckWidget.refresh(app)
    }

    /** Everything the shade is claiming right now — the ongoing row's subtitle reads this. */
    fun waitingSummary(): String? = AttentionAlerts.summary(notified)

    private fun rememberHost(client: BridgeClient) {
        val host = client.lastGoodHost ?: return
        val current = machine ?: return
        _lastGood.value = LastGoodHost(host, System.currentTimeMillis(), Reachability.of(host))
        learnedOnNetwork = currentNetworkHandle()
        if (current.preferredHost == host) return
        val updated = current.copy(preferredHost = host)
        machine = updated
        store.save(updated)
    }

    /**
     * The phone has landed on a different network, so a remembered LAN address is now a five
     * second timeout on every request rather than a shortcut (MP-04).
     *
     * Keyed on the network rather than fired on every callback: `onAvailable` also arrives for
     * the network we are already on, and forgetting there would throw away the shortcut at the
     * desk, which is the one place it is correct.
     */
    private fun forgetHostIfNetworkChanged(network: Network) {
        val current = machine ?: return
        if (learnedOnNetwork == network.networkHandle) return
        learnedOnNetwork = null
        val trimmed = current.forgettingNetworkScopedHost()
        if (trimmed.preferredHost == current.preferredHost) return
        machine = trimmed
        store.save(trimmed)
        _lastGood.value = null
    }

    private fun currentNetworkHandle(): Long? =
        runCatching { connectivity()?.activeNetwork?.networkHandle }.getOrNull()

    /**
     * Coming back to the app asks the machine what is true now (MP-03).
     *
     * Registered on the *process* lifecycle, not an Activity's: the link outlives every screen
     * — that is the whole reason it is a singleton — and an Activity-scoped observer would
     * miss exactly the case this exists for, a process frozen in the background whose socket
     * died while nobody was looking.
     */
    private fun observeForeground() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (machine == null) return
                    val silentFor = SystemClock.elapsedRealtime() - lastAliveAtMs
                    if (LinkPolicy.wakesOnForeground(silentFor)) wake()
                }
            })
        }.onFailure { Log.w(TAG, "No process lifecycle observer; foreground will not wake the link", it) }
    }

    /**
     * What a failure means, and — for the one verdict the app has no business rushing — when
     * it may be said.
     *
     * A pin mismatch, a revoked device and a refusal the machine authored are facts already in
     * hand: they are shown at once, and they are not this method's problem. The last branch is,
     * because "this machine is not answering" is a *guess about the other end* that the very
     * next retry may disprove. It was previously written on the first failed frame, so an
     * ordinary Wi-Fi handover painted an accusation for one second and withdrew it.
     */
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
                    Link.Stale(LinkPolicy.stalenessSentence(machine?.machineName.orEmpty(), machine?.hosts.orEmpty()))
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

    /**
     * The callback is registered for the life of the process and never unregistered, because
     * the thing it protects — the stream — is also process-scoped. Registration can throw on
     * a device that refuses it; a phone without the callback still reconnects on its backoff,
     * so this degrades rather than fails.
     */
    private fun registerNetworkCallback() {
        val manager = connectivity() ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (machine == null) return
                    forgetHostIfNetworkChanged(network)
                    wake()
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

        fun of(context: Context): LiveLink = instance ?: synchronized(this) {
            instance ?: LiveLink(context.applicationContext).also { instance = it }
        }
    }
}
