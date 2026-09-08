package dev.agentdeck.companion

import android.app.Application
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.claudeagents.core.mobile.MobileAnswerRequest
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobilePairingPayload
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledRow
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileStopRequest
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.ApkInstall
import dev.agentdeck.companion.data.AppShortcuts
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.AppUpdate
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PinMismatchException
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.data.SharedInput
import dev.agentdeck.companion.data.Sharing
import dev.agentdeck.companion.push.PushRegistration
import dev.agentdeck.companion.push.UnifiedPush
import dev.agentdeck.companion.data.Snooze
import dev.agentdeck.companion.data.UpdateClient
import dev.agentdeck.companion.data.UpdateFailure
import dev.agentdeck.companion.data.UpdateState
import dev.agentdeck.companion.service.StreamService
import dev.agentdeck.companion.ui.TranscriptTail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A transient message with an optional way back out of what caused it.
 *
 * Successes belong here rather than in a card the user has to dismiss: "stopped", "snoozed",
 * "cancelled" are news, and a modal acknowledgement for news is a tax on every action. A
 * refusal is *not* a success and stays in [DeckState.notice], verbatim and dismissible — the
 * machine's own sentence must not scroll away on a timer.
 */
data class Snack(val id: Long, val message: String, val undoLabel: String? = null)

data class DeckState(
    val screen: Screen = Screen.Pair,
    /** The stack under [screen]; empty when the top is a root destination. */
    val backStack: List<Screen> = emptyList(),
    val machine: PairedMachine? = null,
    val machines: List<PairedMachine> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val link: Link = Link.Connecting,
    val snapshot: MobileFleetSnapshot? = null,
    val readingPositions: dev.agentdeck.companion.data.ReadingPositions = dev.agentdeck.companion.data.ReadingPositions(),
    val filter: FleetFilter = FleetFilter(),
    /** Newest conversation first — see [FleetSort] for why triage is not the opening view. */
    val sort: FleetSort = FleetSort.RECENT,
    val refreshing: Boolean = false,
    /** Conversations the user has swiped away until their agent next moves. */
    val snoozed: Map<String, Long> = emptyMap(),
    /** Null until the composer is opened against a machine that has a project open. */
    val newChatTarget: NewChatTarget? = null,
    /**
     * Text another app shared in, held until the user names a destination for it.
     *
     * Not persisted: a share is a gesture in flight, and one restored a day later would put a
     * banner over a fleet about something the user has long since forgotten sending.
     */
    val sharing: SharedInput? = null,
    val newChatSending: Boolean = false,
    val pendingSends: Set<dev.agentdeck.companion.data.SendAttempt> = emptySet(),
    val transcript: MobileTranscriptPage? = null,
    val transcriptLoading: Boolean = false,
    val earlierLoading: Boolean = false,
    val earlierError: String? = null,
    val earlierExpired: Boolean = false,
    val pendingTranscript: MobileTranscriptPage? = null,
    val findConversationKey: String? = null,
    /** True while the open transcript is the cached copy rather than one this session fetched. */
    val transcriptCached: Boolean = false,
    val scheduled: List<MobileScheduledRow> = emptyList(),
    val scheduledLoading: Boolean = false,
    /** `/v1/hello` — what the machine says it is and what it can do. Settings reads it. */
    val hello: MobileHello? = null,
    /**
     * Which of the machine's addresses carried the last successful call, and when.
     *
     * Diagnostics reads it and nothing else does. It is the difference between "it says it
     * cannot reach my machine" and "it is dialling the LAN address from the train" (MP-07),
     * and it is a question the app could answer all along — `BridgeClient` has always known
     * which host answered — and never asked.
     */
    val lastGood: LastGoodHost? = null,
    /** The plugin's own refusal sentence, shown verbatim until the user acts again. */
    val notice: String? = null,
    val snack: Snack? = null,
    val drafts: Map<String, String> = emptyMap(),
    val pairing: Boolean = false,
    val pairError: String? = null,
    /** True once, after the first pairing, so the notification prompt has a reason to appear. */
    val askNotificationPermission: Boolean = false,
    /** What the app knows about a newer build of itself; see [dev.agentdeck.companion.data.AppUpdate]. */
    val update: UpdateState = UpdateState(),
    /** How this phone is reachable while its app is closed; see [PushState]. */
    val push: PushState = PushState(),
) {
    val destination: Destination? get() = Navigation.destinationOf(screen)
}

/**
 * Where a *late* unpair failure may be shown, as a pure decision so it can be tested without
 * a dispatcher or a fake client.
 *
 * The revoke outlives the local wipe by design, so by the time it fails the user may already
 * have moved on. [pairError] is the pairing screen's field, and `PairScreen` renders it beside
 * the "Pairing…" spinner — so a message about a machine they have left must not land on a live
 * pairing, and must not land *next to* an attempt at the next one either.
 */
/**
 * How this phone can be reached while its app is not running.
 *
 * Four separate facts, deliberately not collapsed into one "push works" boolean: a user whose
 * push is silent needs to know *which* of them is missing, and the four have four different
 * repairs. No distributor installed is an app to install; none chosen is a tap here; no
 * endpoint yet is a wait or a broken relay; and a machine that does not advertise
 * [MobileProtocol.Capability.PUSH] is a switch in the IDE, at the desk, which is the one this
 * screen can only name and never fix.
 */
data class PushState(
    val distributors: List<UnifiedPush.Distributor> = emptyList(),
    val chosen: String? = null,
    val endpoint: String? = null,
    val machineSupports: Boolean = false,
) {
    val chosenLabel: String?
        get() = chosen?.let { name -> distributors.firstOrNull { it.packageName == name }?.label ?: name }

    /** Everything is in place and the machine has agreed to use it. */
    val live: Boolean get() = machineSupports && chosen != null && !endpoint.isNullOrBlank()
}

fun DeckState.withLateUnpairFailure(message: String): DeckState =
    if (screen !is Screen.Pair || pairing) this else copy(pairError = message)

class DeckViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private val live = LiveLink.of(app)

    /**
     * The app's own release manifest. Declared **here**, above `init`, because `init` starts the
     * first check: a property initialized further down the class is still null when the
     * initializer block above it runs (`Memory.md`).
     *
     * It holds no connection, and the address it reads is fixed at build time by
     * `mobile/app/build.gradle.kts` — which is also where `scripts/publish-mobile.sh` reads the
     * repository it publishes to, so the app cannot check a repository nothing uploads to.
     */
    private val updates = UpdateClient(BuildConfig.UPDATE_MANIFEST_URL)

    private val _state = MutableStateFlow(DeckState())
    val state: StateFlow<DeckState> = _state.asStateFlow()

    private var snackCounter = 0L
    private val transcriptRequests = dev.agentdeck.companion.data.TranscriptRequests()
    private var machineGeneration = 0L

    /** What [undoSnack] would undo. Held here rather than in the state: it is a closure. */
    private var pendingUndo: (() -> Unit)? = null

    init {
        val machine = store.paired()
        val settings = store.settings()
        val positions = machine?.let { store.readingPositions(it.id) } ?: dev.agentdeck.companion.data.ReadingPositions()
        val screen = if (machine == null) Screen.Pair
        else Navigation.fromJson(store.screen()) ?: Screen.Fleet
        _state.update {
            it.copy(
                machine = machine,
                machines = store.machines(),
                settings = settings,
                readingPositions = positions,
                filter = positions.fleet.filter,
                sort = positions.fleet.sort,
                screen = screen,
                backStack = Navigation.restoredBackStack(screen),
                drafts = machine?.let { m -> store.drafts(m.id) }.orEmpty(),
            )
        }
        val (installedCode, installedName) = installedVersion()
        _state.update {
            it.copy(
                update = UpdateState(
                    installedCode = installedCode,
                    installedName = installedName,
                    checkedAtMs = store.updateCheckedAt(),
                    dismissedCode = store.updateDismissed(),
                ),
            )
        }
        checkForUpdates()
        live.bind(machine)
        StreamService.reconcile(app, settings.stayConnected && machine != null)
        observeLink()
        (_state.value.screen as? Screen.Conversation)?.let { openTranscript(it.key) }
        if (_state.value.screen is Screen.Scheduled) refreshScheduled()
        if (_state.value.screen == Screen.Scheduled || _state.value.screen == Screen.Settings) refreshHello()
    }

    /**
     * The screens read the connection through this, never directly: the link owns the socket
     * and the fleet, the view model owns what is on screen. Three collectors rather than one
     * combined flow because they arrive on their own schedules, and reading one flow's `.value`
     * inside another's collector inherits that one's emission times (`Memory.md`).
     */
    private fun observeLink() {
        viewModelScope.launch { live.fleet.collect { snapshot -> _state.update { it.copy(snapshot = snapshot) } } }
        viewModelScope.launch {
            live.link.collect { link ->
                val recovered = link == Link.Live && _state.value.link != Link.Live
                _state.update { it.copy(link = link, transcriptCached = it.transcriptCached ||
                    (link != Link.Live && it.transcript != null)) }
                if (recovered) {
                    refreshHello()
                    (_state.value.screen as? Screen.Conversation)?.let { loadTranscript(it.key, quiet = true) }
                    if (_state.value.screen == Screen.Scheduled) refreshScheduled()
                }
            }
        }
        viewModelScope.launch {
            live.lastGood.collect { host -> _state.update { it.copy(lastGood = host) } }
        }
        viewModelScope.launch {
            live.refreshing.collect { busy -> _state.update { it.copy(refreshing = busy) } }
        }
        // The launcher's long-press "most recent thread". Off the *state* rather than off the
        // fleet flow so unpairing withdraws it too — `switchTo(null)` empties the snapshot and
        // never emits a fleet frame, and a shortcut surviving that opens a conversation on a
        // machine this phone no longer talks to.
        viewModelScope.launch {
            state
                .map { s -> s.snapshot?.rows?.maxByOrNull { it.lastActivityMs }?.takeIf { s.machine != null } }
                .distinctUntilChanged { old, new -> old?.key == new?.key && old?.title == new?.title }
                .collect { row -> AppShortcuts.publishRecent(getApplication(), row) }
        }
        viewModelScope.launch {
            live.runs.collect { keys ->
                val open = _state.value.screen as? Screen.Conversation ?: return@collect
                if (TranscriptTail.runFrameConcerns(open.key, keys)) loadTranscript(open.key, quiet = true)
            }
        }
    }

    private fun client(): BridgeClient? = live.client()

    // ---- pairing ---------------------------------------------------------------------

    /** The QR payload and the manual form both land here; there is one pairing path. */
    fun pair(hosts: List<String>, port: Int, fingerprint: String, code: String, label: String) {
        if (_state.value.pairing) return
        _state.update { it.copy(pairing = true, pairError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val client = BridgeClient(hosts, port, fingerprint, token = null)
                    val accepted = client.pair(code, label)
                    PairedMachine(
                        machineName = accepted.machineName,
                        hosts = hosts,
                        port = port,
                        spkiFingerprint = fingerprint,
                        token = accepted.token,
                        deviceId = accepted.deviceId,
                        preferredHost = client.lastGoodHost,
                    )
                }
            }
            outcome.onSuccess { machine ->
                val first = store.machines().isEmpty()
                live.bind(null)
                store.save(machine)
                switchTo(machine)
                _state.update {
                    it.copy(
                        pairing = false,
                        pairError = null,
                        machines = store.machines(),
                        // In context, and only here: the app has just earned the right to ask
                        // by becoming useful, and a cold-start prompt is the one every user
                        // denies without reading.
                        askNotificationPermission = first,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(pairing = false, pairError = describe(error)) }
            }
        }
    }

    fun pairFromQr(raw: String, label: String): Boolean {
        val payload = MobilePairingPayload.decode(raw) ?: return false
        pair(payload.hosts, payload.port, payload.spkiFingerprint, payload.code, label)
        return true
    }

    fun notificationPermissionAsked() = _state.update { it.copy(askNotificationPermission = false) }

    /** Opens the pairing form with the paired machines kept — "add", not "replace". */
    fun addMachine() = go(Screen.Pair)

    fun switchMachine(id: String) {
        val machine = store.machines().firstOrNull { it.id == id } ?: return
        live.bind(null)
        store.activate(id)
        switchTo(machine)
        snack("Showing ${machine.machineName.ifBlank { "this machine" }}")
    }

    /** One place that repoints everything a machine owns: the link, the drafts, the screen. */
    private fun switchTo(machine: PairedMachine?) {
        val positions = machine?.let { store.readingPositions(it.id) } ?: dev.agentdeck.companion.data.ReadingPositions()
        earlierRequest++
        machineGeneration++
        transcriptRequests.reset()
        live.bind(machine)
        _state.update {
            it.copy(
                machine = machine,
                machines = store.machines(),
                screen = if (machine == null) Screen.Pair else Screen.Fleet,
                backStack = emptyList(),
                readingPositions = positions,
                filter = positions.fleet.filter,
                sort = positions.fleet.sort,
                snapshot = machine?.let { m -> store.cachedSnapshot(m.id) },
                drafts = machine?.let { m -> store.drafts(m.id) }.orEmpty(),
                transcript = null,
                transcriptLoading = false,
                transcriptCached = false,
                earlierLoading = false, earlierError = null, earlierExpired = false,
                pendingTranscript = null, findConversationKey = null,
                newChatSending = false,
                scheduled = emptyList(),
                hello = null,
                snoozed = emptyMap(),
                notice = null,
            )
        }
        store.saveScreen(machine?.let { Navigation.toJson(Screen.Fleet) })
        StreamService.reconcile(getApplication(), _state.value.settings.stayConnected && machine != null)
        if (machine != null) live.refresh(initial = true)
    }

    /**
     * Local only. This is the [Link.Repair] route, where the pairing is already dead — a
     * revoked token or a mismatched pin — so there is nothing the machine would accept and
     * nothing worth waiting for.
     */
    fun unpair() {
        val id = _state.value.machine?.id ?: return
        live.bind(null)
        switchTo(store.forget(id))
    }

    /**
     * The user's own way out: switching machines, handing the phone on, or simply done.
     *
     * The local wipe happens **first** so the app answers the tap immediately and so someone
     * unpairing *because* the machine is gone is never trapped by it being unreachable. The
     * machine is then told, on the credential captured a moment earlier: forgetting only the
     * phone's copy would leave the bridge listening for a token the user believes they
     * destroyed. A failed revoke says so on the pairing screen, which is where the user
     * already is, and names the desktop page that finishes the job.
     */
    fun unpairFromMachine() {
        val machine = _state.value.machine ?: return
        val client = BridgeClient(machine)
        unpair()
        viewModelScope.launch {
            val revoked = withContext(Dispatchers.IO) { runCatching { client.unpair() } }
            if (revoked.isFailure) {
                Log.i(TAG, "Unpaired locally; the machine could not be reached", revoked.exceptionOrNull())
                val message = "This phone is unpaired, but ${machine.machineName.ifBlank { "the machine" }} " +
                    "could not be reached to revoke it. Remove this device in the IDE: " +
                    "Settings › Connections › Mobile."
                _state.update { it.withLateUnpairFailure(message) }
            }
        }
    }

    fun dismissPairError() = _state.update { it.copy(pairError = null) }

    // ---- settings ---------------------------------------------------------------------

    /**
     * Offers the installed distributors and remembers which one was chosen.
     *
     * Read on every visit to Settings rather than cached: a distributor is an ordinary app the
     * user may install, uninstall or replace between two openings of this screen, and a stale
     * list would offer a package that no longer exists.
     */
    fun refreshPush() {
        val context = getApplication<android.app.Application>()
        _state.update {
            it.copy(
                push = PushState(
                    distributors = UnifiedPush.distributors(context),
                    chosen = store.pushDistributor(),
                    endpoint = store.pushEndpoint(),
                    machineSupports = it.hello?.capabilities?.contains(MobileProtocol.Capability.PUSH) == true,
                ),
            )
        }
    }

    /**
     * Picks a distributor, or turns push off when [packageName] is null.
     *
     * The endpoint does not arrive here — it comes back later as a broadcast, so the state
     * this writes is deliberately "asked", not "working". A screen that claimed success at the
     * moment of the tap would be reporting the request rather than the transport.
     */
    fun choosePushDistributor(packageName: String?) {
        val context = getApplication<android.app.Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (packageName == null) {
                    PushRegistration.disable(context)
                } else {
                    store.savePushDistributor(packageName)
                    PushRegistration.requestEndpoint(context)
                }
            }
            refreshPush()
        }
    }

    fun updateSettings(settings: AppSettings) {
        store.saveSettings(settings)
        live.settingsChanged(settings)
        StreamService.reconcile(
            getApplication(),
            settings.stayConnected && _state.value.machine != null,
        )
        _state.update { it.copy(settings = settings) }
    }

    /** Capabilities and model choices belong to the connected machine, including after reconnect. */
    fun refreshHello() {
        val client = client() ?: return
        val generation = machineGeneration
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { client.hello() } }
                .onSuccess { hello ->
                    if (generation != machineGeneration) return@launch
                    val discoveredPaging = MobileProtocol.Capability.TRANSCRIPT_PAGING in hello.capabilities &&
                        MobileProtocol.Capability.TRANSCRIPT_PAGING !in _state.value.hello?.capabilities.orEmpty()
                    _state.update { it.copy(hello = hello) }
                    if (discoveredPaging) (_state.value.screen as? Screen.Conversation)?.key?.let {
                        loadTranscript(it, quiet = true)
                    }
                    // The one route by which an *already registered* phone learns the machine
                    // replaced its application-server key; `requestEndpoint` covers the phone
                    // that has not registered yet. Silent unless the key actually moved.
                    withContext(Dispatchers.IO) {
                        PushRegistration.adoptVapidKey(getApplication(), hello.vapidPublicKey)
                    }
                }
                .onFailure { Log.i(TAG, "The machine did not answer /v1/hello", it) }
        }
    }

    // ---- updates ---------------------------------------------------------------------

    /** This build, read from the package manager rather than from `BuildConfig`: the installed
     * APK is the thing being compared, and after a sideload those are the same only by luck. */
    private fun installedVersion(): Pair<Long, String> = runCatching {
        val info = getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0)
        PackageInfoCompat.getLongVersionCode(info) to info.versionName.orEmpty()
    }.getOrElse { 0L to "" }

    /**
     * Reads the published manifest.
     *
     * [manual] is the Settings button and answers whatever the state of the interval is; the
     * automatic call runs at most every [AppUpdate.CHECK_INTERVAL_MS] and only while the user
     * wants to be told. A failure is kept in [UpdateState.error] rather than snacked away: this
     * one is not news, it is the reason the row below it is not offering anything.
     */
    fun checkForUpdates(manual: Boolean = false) {
        if (_state.value.update.busy) return
        val now = System.currentTimeMillis()
        if (!manual) {
            if (!_state.value.settings.updateNotices) return
            if (!AppUpdate.shouldCheck(now, store.updateCheckedAt())) return
        }
        _state.update { it.copy(update = it.update.copy(checking = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { updates.latest() } }
            store.saveUpdateCheckedAt(System.currentTimeMillis())
            _state.update { state ->
                state.copy(
                    update = state.update.copy(
                        checking = false,
                        checkedAtMs = System.currentTimeMillis(),
                        release = result.getOrNull() ?: state.update.release,
                        error = result.exceptionOrNull()?.let { describeUpdate(it) },
                    ),
                )
            }
            result.exceptionOrNull()?.let { Log.i(TAG, "Could not read the release manifest", it) }
        }
    }

    /**
     * Downloads the offered build and hands it straight to Android's installer.
     *
     * Two calls rather than one because the second half can be refused on its own — "install
     * unknown apps" is a per-app setting only the user can flip — and a refusal must not cost
     * the download that already succeeded. [installUpdate] is therefore idempotent and is what
     * the button becomes once the file is on disk.
     */
    fun downloadUpdate() {
        val release = _state.value.update.release ?: return
        if (!_state.value.update.available || _state.value.update.busy) return
        val context = getApplication<Application>()
        val target = ApkInstall.target(context, release)
        _state.update { it.copy(update = it.update.copy(downloadPercent = 0, error = null, readyApk = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ApkInstall.sweep(context, keep = null)
                    updates.download(release, target) { percent ->
                        _state.update { it.copy(update = it.update.copy(downloadPercent = percent)) }
                    }
                }
            }
            _state.update { state ->
                state.copy(
                    update = state.update.copy(
                        downloadPercent = null,
                        readyApk = result.getOrNull()?.absolutePath,
                        error = result.exceptionOrNull()?.let { describeUpdate(it) },
                    ),
                )
            }
            result.onSuccess { installUpdate() }
                .onFailure { Log.w(TAG, "The update did not download", it) }
        }
    }

    /** Opens the system installer on the downloaded file, or says which of the two things stopped it. */
    fun installUpdate() {
        val path = _state.value.update.readyApk ?: return
        val context = getApplication<Application>()
        if (!ApkInstall.canInstall(context)) {
            // The sentence is written after the trip, not before it: "turn that on and press
            // Install again" is an instruction only when the page that turns it on opened.
            val opened = ApkInstall.requestPermission(context)
            failInstall(
                if (opened) {
                    "Android has not been allowed to install apps from Agent Deck. " +
                        "Turn that on and press Install again."
                } else {
                    "Android has not been allowed to install apps from Agent Deck, and this " +
                        "phone has no page that turns that on. Open the release page below and " +
                        "install it by hand."
                },
            )
            return
        }
        if (!ApkInstall.launch(context, java.io.File(path))) {
            failInstall("This phone has nothing that installs an APK.")
            return
        }
        // The installer is on screen. A refusal from a *previous* press outranks "Downloaded."
        // in `AppUpdate.status`, so leaving it behind describes a state the phone is not in.
        _state.update { it.copy(update = it.update.copy(error = null)) }
    }

    private fun failInstall(message: String) =
        _state.update { it.copy(update = it.update.copy(error = message)) }

    /** Hides the banner for this published build only; a newer one raises it again. */
    fun dismissUpdate() {
        val code = _state.value.update.release?.versionCode ?: return
        store.saveUpdateDismissed(code)
        _state.update { it.copy(update = it.update.copy(dismissedCode = code)) }
    }

    /** The way out of every refusal above: the release page, where the APK can be fetched by hand. */
    fun openReleasePage() {
        val url = _state.value.update.release?.releaseUrl?.takeIf { it.isNotBlank() }
            ?: BuildConfig.UPDATE_RELEASES_URL
        ApkInstall.openPage(getApplication(), url)
    }

    // ---- fleet -----------------------------------------------------------------------

    fun refreshFleet(initial: Boolean = false) = live.refresh(initial)

    fun setFilter(filter: FleetFilter) {
        _state.update { it.copy(filter = filter) }
        updateBrowsing { it.copy(filter = filter, anchor = dev.agentdeck.companion.data.ReadingAnchor(followingLatest = false)) }
    }

    fun setSort(sort: FleetSort) {
        _state.update { it.copy(sort = sort) }
        updateBrowsing { it.copy(sort = sort, anchor = dev.agentdeck.companion.data.ReadingAnchor(followingLatest = false)) }
    }

    private fun updateBrowsing(change: (dev.agentdeck.companion.data.FleetBrowsing) -> dev.agentdeck.companion.data.FleetBrowsing) {
        val machineId = _state.value.machine?.id ?: return
        val positions = _state.value.readingPositions.let { it.copy(fleet = change(it.fleet)) }
        _state.update { it.copy(readingPositions = positions) }
        store.saveReadingPositions(machineId, positions)
    }

    fun rememberReading(machineId: String?, key: String, anchor: dev.agentdeck.companion.data.ReadingAnchor) {
        if (machineId == null || _state.value.machine?.id != machineId) return
        val positions = _state.value.readingPositions.remembering(key, anchor)
        _state.update { it.copy(readingPositions = positions) }
        store.saveReadingPositions(machineId, positions)
    }

    fun rememberBrowsing(machineId: String?, browsing: dev.agentdeck.companion.data.FleetBrowsing) {
        if (machineId == null || _state.value.machine?.id != machineId) return
        // A departing animation may still report its old list after a filter change.
        if (browsing.filter != _state.value.filter || browsing.sort != _state.value.sort) return
        updateBrowsing { browsing }
    }

    /**
     * The badge's own gesture: show the rows it counts. It is on the Fleet destination now, so
     * the tap that says "3 agents need you" has to end on those three rows rather than on a
     * re-fetch — which is what it did while the badge lived on Refresh.
     */
    fun showWaiting() {
        _state.update {
            it.copy(
                screen = Screen.Fleet,
                backStack = emptyList(),
                sort = FleetSort.ATTENTION,
                filter = FleetFilter(),
            )
        }
        store.saveScreen(Navigation.toJson(Screen.Fleet))
    }

    /**
     * Hides a row until its agent next moves, and says so with a way back.
     *
     * This is triage, not classification: the desktop still owns what state the conversation is
     * in ([dev.agentdeck.companion.data.FleetGrouping]), and the snooze is keyed to the activity
     * stamp it was taken at, so the row returns by itself the moment anything happens in it.
     */
    fun snooze(row: MobileFleetRow) {
        val previous = _state.value.snoozed
        _state.update { it.copy(snoozed = Snooze.add(previous, row)) }
        snack("Snoozed \"${row.title.ifBlank { "this conversation" }}\"", undoLabel = "Undo") {
            _state.update { it.copy(snoozed = previous) }
        }
    }

    // ---- new chat ---------------------------------------------------------------------

    /**
     * Opens the composer even when the machine has nothing open: the screen says so, where a
     * disabled button on the fleet would leave the user with nothing to read.
     */
    fun openNewChat() {
        push(Screen.NewChat)
        consumeShare(NEW_CHAT_DRAFT_KEY)
        // The model picker is drawn from `/v1/hello`, and this screen is reached from the
        // fleet — which never asks. Without this the ladder is whatever Settings last saw.
        refreshHello()
        _state.update {
            it.copy(
                notice = null,
                newChatTarget = NewChat.defaultTarget(
                    openProjects = it.snapshot?.openProjects.orEmpty(),
                    rows = it.snapshot?.rows.orEmpty(),
                    previous = it.newChatTarget,
                ),
            )
        }
    }

    fun setNewChatTarget(target: NewChatTarget) = _state.update { it.copy(newChatTarget = target) }

    // ---- the share sheet ---------------------------------------------------------------

    /**
     * Another app handed this one some text. It goes nowhere until the user picks a row.
     *
     * The fleet *is* the picker — every conversation, already filtered, sorted and searchable,
     * with the New-chat FAB in the corner. A second list built for this would be a worse copy
     * of it one screen away. An unpaired phone keeps the payload and stays on the pairing
     * screen: [switchTo] lands on Fleet, where the banner is then waiting.
     */
    fun share(input: SharedInput) {
        _state.update { it.copy(sharing = input, notice = null) }
        if (_state.value.machine != null) go(Screen.Fleet)
    }

    fun dismissShare() = _state.update { it.copy(sharing = null) }

    /**
     * Moves a pending share into [key]'s draft, and returns the state with it consumed.
     *
     * Appended rather than assigned ([Sharing.appendedTo]): the destination may already hold
     * a half-written question, and this app's one promise about drafts is that it keeps them.
     */
    private fun consumeShare(key: String) {
        val shared = _state.value.sharing ?: return
        _state.update { it.copy(sharing = null) }
        setDraft(key, Sharing.appendedTo(_state.value.drafts[key].orEmpty(), shared.text))
    }

    /**
     * Starts a conversation on the machine. Like every other send here, a refusal **keeps
     * the draft** — the prompt is the whole of what the user typed, and clearing it on a
     * failure would look exactly like a chat that started.
     */
    fun startNewChat() {
        val client = client() ?: return
        val state = _state.value
        val target = state.newChatTarget ?: return
        val prompt = state.drafts[NEW_CHAT_DRAFT_KEY].orEmpty()
        if (prompt.isBlank() || state.newChatSending) return
        val generation = machineGeneration
        _state.update { it.copy(newChatSending = true, notice = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    client.send(
                        MobileSendRequest(
                            key = null,
                            projectPath = target.projectPath,
                            prompt = prompt,
                            vendor = target.vendor,
                            // Null is the machine's own default — the field is then absent
                            // from the request, which is what this screen always sent.
                            model = target.model,
                            effort = null,
                            permissionMode = null,
                            newChat = true,
                        ),
                    )
                }
            }
            if (generation != machineGeneration) return@launch
            outcome.onSuccess {
                clearSentDraft(NEW_CHAT_DRAFT_KEY, prompt)
                // The machine answers "queued" and cannot name a conversation that does not
                // exist yet, so the honest destination is the list the new chat appears in.
                _state.update { it.copy(newChatSending = false) }
                if (_state.value.screen == Screen.NewChat) go(Screen.Fleet)
                snack("Started on ${target.projectPath.substringAfterLast('/')}")
                refreshFleet()
            }.onFailure { error ->
                _state.update { it.copy(newChatSending = false,
                    notice = if (it.screen == Screen.NewChat) describeSend(error) else it.notice) }
                if (_state.value.screen != Screen.NewChat) snack(describeSend(error))
                if (fatal(error)) live.failLink(error)
            }
        }
    }

    // ---- conversation ----------------------------------------------------------------

    fun openConversation(row: MobileFleetRow) {
        push(Screen.Conversation(row.key, row.title, row.vendor, row.projectPath))
        consumeShare(row.key)
        openTranscript(row.key)
    }

    /** A deep link carries a key and may carry nothing else; the snapshot fills in the rest. */
    fun open(link: DeepLink) {
        when (link) {
            DeepLink.Fleet -> go(Screen.Fleet)
            DeepLink.Scheduled -> {
                go(Screen.Scheduled)
                refreshScheduled()
                refreshHello()
            }
            DeepLink.Settings -> {
                go(Screen.Settings)
                refreshHello()
            }
            DeepLink.NewChat -> openNewChat()
            is DeepLink.Conversation -> {
                val known = _state.value.snapshot?.rows?.firstOrNull { it.key == link.key }
                push(
                    Screen.Conversation(
                        key = link.key,
                        title = known?.title ?: link.title.orEmpty(),
                        vendor = known?.vendor ?: link.vendor ?: com.github.claudeagents.core.AgentVendor.CLAUDE,
                        projectPath = known?.projectPath ?: link.projectPath.orEmpty(),
                    ),
                )
                openTranscript(link.key)
            }
        }
    }

    /**
     * Paints the cached copy first, then refreshes.
     *
     * A conversation read five minutes ago on Wi-Fi used to be blank in a tunnel: nothing but
     * the fleet snapshot was cached. The cached page keeps its own `generatedAtMs`, and the
     * screen stamps itself from that — so this is never a stale page pretending to be live.
     */
    private var earlierRequest = 0L
    private var earlierAutomaticCount = 0
    private var forceTranscriptKey: String? = null
    private var transcriptActivation = 0L

    private fun openTranscript(key: String) {
        val activation = ++transcriptActivation
        val generation = machineGeneration
        earlierRequest++
        earlierAutomaticCount = 0
        forceTranscriptKey = null
        transcriptRequests.reset()
        live.readingKey = key
        val machineId = _state.value.machine?.id
        _state.update {
            it.copy(transcript = null, transcriptLoading = true, transcriptCached = false, notice = null,
                earlierLoading = false, earlierError = null, earlierExpired = false, pendingTranscript = null,
                findConversationKey = null)
        }
        refreshHello()
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { machineId?.let { store.cachedTranscript(it, key) } }
            if (activation != transcriptActivation || generation != machineGeneration ||
                (_state.value.screen as? Screen.Conversation)?.key != key) return@launch
            if (_state.value.transcript == null) {
                _state.update { it.copy(transcript = cached, transcriptCached = cached != null) }
            }
            loadTranscript(key, quiet = _state.value.transcript != null)
        }
    }

    fun loadTranscript(key: String, quiet: Boolean = false) {
        if ((_state.value.screen as? Screen.Conversation)?.key != key) return
        val client = client() ?: return
        val machineId = _state.value.machine?.id ?: return
        val generation = machineGeneration
        val request = transcriptRequests.begin(machineId, key) ?: return
        val force = forceTranscriptKey == key
        val paging = MobileProtocol.Capability.TRANSCRIPT_PAGING in _state.value.hello?.capabilities.orEmpty()
        if (!quiet) _state.update { it.copy(transcriptLoading = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    client.transcript(key, paging = paging).also {
                        require(it.key == key) { "The IDE returned a different conversation. Open this chat again to retry." }
                    }
                }
            }
            if (generation != machineGeneration || !transcriptRequests.owns(request)) return@launch
            val refreshAgain = transcriptRequests.finish(request)
            if ((_state.value.screen as? Screen.Conversation)?.key != key) return@launch
            outcome.onSuccess { page ->
                val current = _state.value
                val old = current.transcript
                val following = current.readingPositions.conversations[key]?.followingLatest != false
                val retained = if (force) page else dev.agentdeck.companion.data.TranscriptPages.refresh(old, page, following)
                if (retained != null) {
                    if (force) forceTranscriptKey = null
                    _state.update { it.copy(transcript = retained, transcriptLoading = false,
                        transcriptCached = false, pendingTranscript = null,
                        earlierError = if (force) null else it.earlierError,
                        earlierExpired = if (force) false else it.earlierExpired) }
                    cacheDisplayedTranscript(machineId, generation, retained)
                    if (retained.historyPending && !retained.historyPaused) loadEarlier(key, automatic = true)
                } else {
                    _state.update { it.copy(transcriptLoading = false, pendingTranscript = page) }
                }
            }.onFailure { error ->
                _state.update { it.copy(transcriptLoading = false,
                    earlierError = if (force) describe(error) else it.earlierError,
                    earlierExpired = if (force) true else it.earlierExpired) }
                if (!force) live.failLink(error)
            }
            if (refreshAgain) loadTranscript(key, quiet = true)
        }
    }

    private fun cacheDisplayedTranscript(machineId: String, generation: Long, page: MobileTranscriptPage) {
        viewModelScope.launch(Dispatchers.IO) {
            store.cacheTranscript(machineId, page) {
                generation == machineGeneration && _state.value.machine?.id == machineId &&
                    _state.value.transcript === page
            }
        }
    }

    fun findInConversation(open: Boolean) {
        _state.update { it.copy(findConversationKey = if (open) (it.screen as? Screen.Conversation)?.key else null) }
    }

    fun acceptLatest(key: String) {
        val page = _state.value.pendingTranscript?.takeIf { it.key == key } ?: return
        earlierRequest++
        _state.update { it.copy(transcript = page, pendingTranscript = null, transcriptCached = false,
            earlierLoading = false, earlierError = null, earlierExpired = false) }
        val machineId = _state.value.machine?.id ?: return
        cacheDisplayedTranscript(machineId, machineGeneration, page)
    }

    fun refreshHistory(key: String) {
        earlierRequest++
        _state.update { it.copy(earlierError = null, earlierExpired = false, earlierLoading = false) }
        transcriptRequests.reset()
        forceTranscriptKey = key
        loadTranscript(key, quiet = true)
    }

    fun loadEarlier(key: String, automatic: Boolean = false) {
        val state = _state.value
        if ((state.screen as? Screen.Conversation)?.key != key || state.earlierLoading) return
        if (MobileProtocol.Capability.TRANSCRIPT_PAGING !in state.hello?.capabilities.orEmpty()) return
        val page = state.transcript?.takeIf { it.key == key } ?: return
        val cursor = page.previousCursor ?: return
        val machineId = state.machine?.id ?: return
        val client = client() ?: return
        if (!automatic) earlierAutomaticCount = 0
        if (earlierAutomaticCount >= 8) return
        earlierAutomaticCount++
        val generation = machineGeneration
        val request = ++earlierRequest
        _state.update { it.copy(earlierLoading = true, earlierError = null, earlierExpired = false) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.transcript(key, paging = true, before = cursor) }
            }
            if (generation != machineGeneration || request != earlierRequest ||
                (_state.value.screen as? Screen.Conversation)?.key != key) return@launch
            outcome.onSuccess { earlier ->
                val current = _state.value.transcript
                if (current?.revision != page.revision || current?.previousCursor != cursor) {
                    _state.update { it.copy(earlierLoading = false) }
                    return@onSuccess
                }
                runCatching { dev.agentdeck.companion.data.TranscriptPages.prepend(current, earlier) }
                    .onSuccess { merged ->
                        _state.update { it.copy(transcript = merged, earlierLoading = false) }
                        cacheDisplayedTranscript(machineId, generation, merged)
                        if (merged.historyPending && !merged.historyPaused) loadEarlier(key, automatic = true)
                    }.onFailure {
                        _state.update { it.copy(earlierLoading = false, earlierError = "History changed. Refresh to load earlier messages.", earlierExpired = true) }
                    }
            }.onFailure { error ->
                val expired = (error as? BridgeRefusal)?.code == "stale-cursor"
                _state.update { it.copy(earlierLoading = false, earlierError = describe(error), earlierExpired = expired) }
            }
        }
    }

    fun setDraft(key: String, text: String) {
        val machineId = _state.value.machine?.id
        val drafts = _state.value.drafts + (key to text)
        _state.update { it.copy(drafts = drafts) }
        machineId?.let { store.saveDrafts(it, drafts) }
    }

    fun draft(key: String): String = _state.value.drafts[key].orEmpty()

    /**
     * Sends, and on refusal **keeps the draft**. A refused send that cleared the composer
     * would be indistinguishable from a delivered one, which is the single worst thing this
     * screen can do — the user would believe an agent had been told something it never was.
     */
    fun send(target: Screen.Conversation, prompt: String, stopFirst: Boolean) {
        val client = client() ?: return
        val machineId = _state.value.machine?.id ?: return
        if (prompt.isBlank()) return
        val attempt = dev.agentdeck.companion.data.SendAttempt(machineId, target.key, prompt)
        if (attempt in _state.value.pendingSends) return
        val generation = machineGeneration
        _state.update { it.copy(notice = null, pendingSends = it.pendingSends + attempt) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (stopFirst) client.stop(MobileStopRequest(target.key))
                    client.send(MobileSendRequest(
                        key = target.key, projectPath = target.projectPath, prompt = prompt,
                        vendor = target.vendor, model = null, effort = null,
                        permissionMode = null, newChat = false,
                    ))
                }
            }
            _state.update { it.copy(pendingSends = it.pendingSends - attempt) }
            if (generation != machineGeneration) return@launch
            outcome.onSuccess {
                clearSentDraft(target.key, prompt)
                if ((_state.value.screen as? Screen.Conversation)?.key == target.key) {
                    loadTranscript(target.key, quiet = true)
                }
                snack("Sent to ${target.vendor.name.lowercase().replaceFirstChar(Char::uppercase)}")
            }.onFailure { error ->
                if ((_state.value.screen as? Screen.Conversation)?.key == target.key) {
                    _state.update { it.copy(notice = describeSend(error)) }
                } else snack(describeSend(error))
                if (fatal(error)) live.failLink(error)
            }
        }
    }

    private fun clearSentDraft(key: String, sent: String) {
        val current = draft(key)
        val remaining = dev.agentdeck.companion.data.SendAttempt.remainingDraft(current, sent)
        if (remaining != current) setDraft(key, remaining)
    }

    private fun describeSend(error: Throwable): String = when (error) {
        is BridgeRefusal, is PinMismatchException -> describe(error)
        else -> "Delivery could not be confirmed. Your draft is saved. Check the conversation before sending again."
    }

    /**
     * Answers a parked `AskUserQuestion` from the transcript's own choice cards.
     *
     * The snack is not decoration: the two routes the machine can take are different acts, and
     * the reader has to be able to tell them apart. `parked` means the pick went back on the
     * control channel and the turn the agent was blocked on resumed. Not parked means the ask
     * was gone — answered at the desk, or the run is over — and the pick was sent as an
     * ordinary prompt, which starts a *new* turn and will be billed as one.
     */
    fun answer(target: Screen.Conversation, questionKey: String, label: String) {
        val client = client() ?: return
        val generation = machineGeneration
        if (questionKey.isBlank() || label.isBlank()) return
        _state.update { it.copy(notice = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    client.answer(MobileAnswerRequest(target.key, mapOf(questionKey to label)))
                }
            }
            if (generation != machineGeneration) return@launch
            outcome.onSuccess { accepted ->
                snack(if (accepted.parked) "Answer sent" else "That question had gone — sent as a new message")
                loadTranscript(target.key, quiet = true)
                refreshFleet()
            }.onFailure { error ->
                _state.update { it.copy(notice = describe(error)) }
                if (fatal(error)) live.failLink(error)
            }
        }
    }

    fun stop(key: String, announce: String? = null) {
        val client = client() ?: return
        val generation = machineGeneration
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.stop(MobileStopRequest(key)) }
            }
            if (generation != machineGeneration) return@launch
            outcome
                .onSuccess { announce?.let(::snack) }
                .onFailure { error -> _state.update { it.copy(notice = describe(error)) } }
            loadTranscript(key, quiet = true)
            refreshFleet()
        }
    }

    // ---- scheduled -------------------------------------------------------------------

    fun openScheduled() {
        go(Screen.Scheduled)
        refreshScheduled()
    }

    fun refreshScheduled() {
        val client = client() ?: return
        val generation = machineGeneration
        _state.update { it.copy(scheduledLoading = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.scheduled() } }
            if (generation != machineGeneration) return@launch
            outcome.onSuccess { list ->
                _state.update { it.copy(scheduled = list.rows, scheduledLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(scheduledLoading = false, notice = describe(error)) }
            }
        }
    }

    /**
     * Queues a prompt for later. The same `/v1/send` every other prompt travels, plus a due
     * time — the machine turns *every* phone-origin prompt into a scheduled row, so a queued
     * one needs no second write path and inherits every guard the immediate one has.
     */
    fun createSchedule(projectPath: String, dueAtMs: Long, model: String?) {
        val client = client() ?: return
        val generation = machineGeneration
        val prompt = _state.value.drafts[SCHEDULE_DRAFT_KEY].orEmpty()
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    client.send(
                        MobileSendRequest(
                            key = null,
                            projectPath = projectPath,
                            prompt = prompt,
                            vendor = com.github.claudeagents.core.AgentVendor.CLAUDE,
                            model = model,
                            effort = null,
                            permissionMode = null,
                            newChat = true,
                            dueAtMs = dueAtMs,
                        ),
                    )
                }
            }
            if (generation != machineGeneration) return@launch
            outcome.onSuccess {
                // Consumed, so the draft goes — the rule is that unsent text survives, and
                // this one is no longer unsent.
                clearSentDraft(SCHEDULE_DRAFT_KEY, prompt)
                snack(
                    "Scheduled for " +
                        dev.agentdeck.companion.ui.Times.clock(dueAtMs, System.currentTimeMillis()),
                )
                refreshScheduled()
            }.onFailure { error ->
                // The draft is untouched: a refused schedule that cleared it would lose the
                // prompt the user wrote and look like one that was queued.
                _state.update { it.copy(notice = describe(error)) }
            }
        }
    }

    /**
     * [ids] is always explicit, including for "cancel all" — the plugin's protocol requires
     * it so a row that arrived after the user looked at the list cannot be cancelled by a
     * request that means "everything".
     */
    fun scheduledCommand(action: String, ids: List<String>, announce: String? = null) {
        val client = client() ?: return
        val generation = machineGeneration
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.scheduledCommand(MobileScheduledCommand(action, ids)) }
            }
            if (generation != machineGeneration) return@launch
            outcome
                .onSuccess { announce?.let(::snack) }
                .onFailure { error -> _state.update { it.copy(notice = describe(error)) } }
            refreshScheduled()
        }
    }

    // ---- navigation ------------------------------------------------------------------

    /** Switches root destination. The stack is dropped: tabs are places, not history. */
    fun go(destination: Destination) {
        go(Navigation.screenOf(destination))
        when (destination) {
            // Both need `/v1/hello`: Settings shows what the machine says it is, and Scheduled
            // shows the create button only where the machine says it honours a due time.
            Destination.SCHEDULED -> {
                refreshScheduled()
                refreshHello()
            }
            Destination.SETTINGS -> refreshHello()
            Destination.FLEET -> Unit
        }
    }

    private fun go(screen: Screen) {
        live.readingKey = null
        _state.update { it.copy(screen = screen, backStack = emptyList(), notice = null) }
        store.saveScreen(Navigation.toJson(screen))
    }

    private fun push(screen: Screen) {
        _state.update {
            it.copy(screen = screen, backStack = it.backStack + it.screen, notice = null)
        }
        store.saveScreen(Navigation.toJson(screen))
    }

    /** True when the gesture was consumed; false means the system may finish the activity. */
    fun back(): Boolean {
        val state = _state.value
        if (state.findConversationKey != null) {
            findInConversation(false)
            return true
        }
        val previous = Navigation.backTarget(state.screen, state.backStack) ?: return false
        live.readingKey = null
        _state.update {
            it.copy(screen = previous, backStack = it.backStack.dropLast(1), notice = null)
        }
        store.saveScreen(Navigation.toJson(previous))
        if (previous is Screen.Conversation) openTranscript(previous.key)
        return true
    }

    // ---- messages --------------------------------------------------------------------

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun snack(message: String, undoLabel: String? = null, undo: (() -> Unit)? = null) {
        pendingUndo = undo
        snackCounter += 1
        _state.update { it.copy(snack = Snack(snackCounter, message, undoLabel.takeIf { undo != null })) }
    }

    fun undoSnack() {
        pendingUndo?.invoke()
        pendingUndo = null
        _state.update { it.copy(snack = null) }
    }

    fun snackShown(id: Long) = _state.update { if (it.snack?.id == id) it.copy(snack = null) else it }

    private fun fatal(error: Throwable): Boolean =
        error is PinMismatchException || (error as? BridgeRefusal)?.isRevoked == true

    /**
     * A refusal's own sentence, verbatim. Anything else gets a transport-level description;
     * the app never puts its own wording behind the machine's voice.
     */
    /**
     * The download route's own sentence. [describe] speaks for the bridge and answers every
     * [IOException] with "the IDE has to be running", which is a claim about a machine this
     * route never dialled.
     */
    private fun describeUpdate(error: Throwable): String = when (error) {
        is UpdateFailure -> error.message.orEmpty()
        is IOException -> "Could not reach the download page. Check this phone's connection."
        else -> error.message ?: "Something went wrong."
    }

    private fun describe(error: Throwable): String = when (error) {
        is BridgeRefusal -> error.message
        is PinMismatchException -> error.message.orEmpty()
        is IOException -> "Could not reach this machine. The IDE has to be running."
        else -> error.message ?: "Something went wrong."
    }

    companion object {
        private const val TAG = "AgentDeck"

        /** The Schedule dialog's prompt, kept across close/reopen like every other field. */
        const val SCHEDULE_DRAFT_KEY = "scheduled-prompt"
    }
}
