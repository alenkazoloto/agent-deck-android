package dev.agentdeck.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.github.claudeagents.core.mobile.MobileBroadcast
import com.github.claudeagents.core.mobile.MobileEvent
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.ambient.DeckWidget
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PinMismatchException
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

    init {
        registerNetworkCallback()
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
     * The SSE stream. Reconnects with `Last-Event-ID`, backing off, and gives up into
     * [Link.Stale] rather than spinning: a phone that cannot reach the machine should say
     * so and show the age of what it has, not imply that data is arriving.
     */
    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            var lastId: String? = null
            var backoffMs = 1_000L
            while (isActive) {
                val client = client() ?: return@launch
                val outcome = runCatching {
                    client.stream(lastId) { frame ->
                        frame.id?.let { lastId = it }
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
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
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
        if (current.preferredHost == host) return
        val updated = current.copy(preferredHost = host)
        machine = updated
        store.save(updated)
    }

    fun failLink(error: Throwable) {
        val pin = error as? PinMismatchException
        val refusal = error as? BridgeRefusal
        _refreshing.value = false
        when {
            pin != null -> _link.value = Link.Repair(pin.message.orEmpty())
            refusal != null && refusal.isRevoked -> _link.value = Link.Repair(refusal.message)
            refusal != null -> _link.value = Link.Stale(refusal.message)
            !online() -> _link.value = Link.Offline
            else -> {
                Log.i(TAG, "Bridge unreachable", error)
                _link.value = Link.Stale("This machine is not answering. The IDE has to be running.")
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
                    if (machine != null) wake()
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
