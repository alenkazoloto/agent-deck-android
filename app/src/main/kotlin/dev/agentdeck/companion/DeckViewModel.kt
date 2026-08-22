package dev.agentdeck.companion

import android.app.Application
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobilePairingPayload
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledRow
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileStopRequest
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.ApkInstall
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
    val filter: FleetFilter = FleetFilter(),
    /** Newest conversation first — see [FleetSort] for why triage is not the opening view. */
    val sort: FleetSort = FleetSort.RECENT,
    val refreshing: Boolean = false,
    /** Conversations the user has swiped away until their agent next moves. */
    val snoozed: Map<String, Long> = emptyMap(),
    /** Null until the composer is opened against a machine that has a project open. */
    val newChatTarget: NewChatTarget? = null,
    val newChatSending: Boolean = false,
    val transcript: MobileTranscriptPage? = null,
    val transcriptLoading: Boolean = false,
    /** True while the open transcript is the cached copy rather than one this session fetched. */
    val transcriptCached: Boolean = false,
    val scheduled: List<MobileScheduledRow> = emptyList(),
    val scheduledLoading: Boolean = false,
    /** `/v1/hello` — what the machine says it is and what it can do. Settings reads it. */
    val hello: MobileHello? = null,
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

    /** What [undoSnack] would undo. Held here rather than in the state: it is a closure. */
    private var pendingUndo: (() -> Unit)? = null

    init {
        val machine = store.paired()
        val settings = store.settings()
        _state.update {
            it.copy(
                machine = machine,
                machines = store.machines(),
                settings = settings,
                screen = when {
                    machine == null -> Screen.Pair
                    else -> Navigation.fromJson(store.screen()) ?: Screen.Fleet
                },
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
    }

    /**
     * The screens read the connection through this, never directly: the link owns the socket
     * and the fleet, the view model owns what is on screen. Three collectors rather than one
     * combined flow because they arrive on their own schedules, and reading one flow's `.value`
     * inside another's collector inherits that one's emission times (`Memory.md`).
     */
    private fun observeLink() {
        viewModelScope.launch { live.fleet.collect { snapshot -> _state.update { it.copy(snapshot = snapshot) } } }
        viewModelScope.launch { live.link.collect { link -> _state.update { it.copy(link = link) } } }
        viewModelScope.launch {
            live.refreshing.collect { busy -> _state.update { it.copy(refreshing = busy) } }
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
        store.activate(id)
        switchTo(machine)
        snack("Showing ${machine.machineName.ifBlank { "this machine" }}")
    }

    /** One place that repoints everything a machine owns: the link, the drafts, the screen. */
    private fun switchTo(machine: PairedMachine?) {
        live.bind(machine)
        _state.update {
            it.copy(
                machine = machine,
                machines = store.machines(),
                screen = if (machine == null) Screen.Pair else Screen.Fleet,
                backStack = emptyList(),
                snapshot = machine?.let { m -> store.cachedSnapshot(m.id) },
                drafts = machine?.let { m -> store.drafts(m.id) }.orEmpty(),
                transcript = null,
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

    fun updateSettings(settings: AppSettings) {
        store.saveSettings(settings)
        live.settingsChanged(settings)
        StreamService.reconcile(
            getApplication(),
            settings.stayConnected && _state.value.machine != null,
        )
        _state.update { it.copy(settings = settings) }
    }

    /** `/v1/hello` — fetched when Settings opens, because that is the only screen that reads it. */
    fun refreshHello() {
        val client = client() ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { client.hello() } }
                .onSuccess { hello -> _state.update { it.copy(hello = hello) } }
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
            _state.update {
                it.copy(
                    update = it.update.copy(
                        error = "Android has not been allowed to install apps from Agent Deck. " +
                            "Turn that on and press Install again.",
                    ),
                )
            }
            ApkInstall.requestPermission(context)
            return
        }
        if (!ApkInstall.launch(context, java.io.File(path))) {
            _state.update {
                it.copy(update = it.update.copy(error = "This phone has nothing that installs an APK."))
            }
        }
    }

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

    fun setFilter(filter: FleetFilter) = _state.update { it.copy(filter = filter) }

    fun setSort(sort: FleetSort) = _state.update { it.copy(sort = sort) }

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
                            model = null,
                            effort = null,
                            permissionMode = null,
                            newChat = true,
                        ),
                    )
                }
            }
            outcome.onSuccess {
                setDraft(NEW_CHAT_DRAFT_KEY, "")
                // The machine answers "queued" and cannot name a conversation that does not
                // exist yet, so the honest destination is the list the new chat appears in.
                _state.update { it.copy(newChatSending = false) }
                go(Screen.Fleet)
                snack("Started on ${target.projectPath.substringAfterLast('/')}")
                refreshFleet()
            }.onFailure { error ->
                _state.update { it.copy(newChatSending = false, notice = describe(error)) }
                if (fatal(error)) live.failLink(error)
            }
        }
    }

    // ---- conversation ----------------------------------------------------------------

    fun openConversation(row: MobileFleetRow) {
        push(Screen.Conversation(row.key, row.title, row.vendor, row.projectPath))
        openTranscript(row.key)
    }

    /** A deep link carries a key and may carry nothing else; the snapshot fills in the rest. */
    fun open(link: DeepLink) {
        when (link) {
            DeepLink.Fleet -> go(Screen.Fleet)
            DeepLink.Scheduled -> {
                go(Screen.Scheduled)
                refreshScheduled()
            }
            DeepLink.Settings -> {
                go(Screen.Settings)
                refreshHello()
            }
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
    private fun openTranscript(key: String) {
        live.readingKey = key
        val machineId = _state.value.machine?.id
        val cached = machineId?.let { store.cachedTranscript(it, key) }
        _state.update {
            it.copy(transcript = cached, transcriptCached = cached != null, notice = null)
        }
        loadTranscript(key, quiet = cached != null)
    }

    fun loadTranscript(key: String, quiet: Boolean = false) {
        val client = client() ?: return
        if (!quiet) _state.update { it.copy(transcriptLoading = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.transcript(key) } }
            outcome.onSuccess { page ->
                _state.value.machine?.let { store.cacheTranscript(it.id, page) }
                _state.update {
                    // A late reply for a conversation the user already left must not paint.
                    if ((it.screen as? Screen.Conversation)?.key != key) it
                    else it.copy(transcript = page, transcriptLoading = false, transcriptCached = false)
                }
            }.onFailure { error ->
                _state.update { it.copy(transcriptLoading = false) }
                live.failLink(error)
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
        if (prompt.isBlank()) return
        _state.update { it.copy(notice = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (stopFirst) client.stop(MobileStopRequest(target.key))
                    client.send(
                        MobileSendRequest(
                            key = target.key,
                            projectPath = target.projectPath,
                            prompt = prompt,
                            vendor = target.vendor,
                            model = null,
                            effort = null,
                            permissionMode = null,
                            newChat = false,
                        ),
                    )
                }
            }
            outcome.onSuccess {
                setDraft(target.key, "")
                loadTranscript(target.key, quiet = true)
            }.onFailure { error ->
                // The draft is untouched on purpose; only the notice changes.
                _state.update { it.copy(notice = describe(error)) }
                if (fatal(error)) live.failLink(error)
            }
        }
    }

    fun stop(key: String, announce: String? = null) {
        val client = client() ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.stop(MobileStopRequest(key)) }
            }
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
        _state.update { it.copy(scheduledLoading = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.scheduled() } }
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
    fun createSchedule(projectPath: String, dueAtMs: Long) {
        val client = client() ?: return
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
                            model = null,
                            effort = null,
                            permissionMode = null,
                            newChat = true,
                            dueAtMs = dueAtMs,
                        ),
                    )
                }
            }
            outcome.onSuccess {
                // Consumed, so the draft goes — the rule is that unsent text survives, and
                // this one is no longer unsent.
                setDraft(SCHEDULE_DRAFT_KEY, "")
                snack("Scheduled for ${dev.agentdeck.companion.ui.Times.clock(dueAtMs)}")
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
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.scheduledCommand(MobileScheduledCommand(action, ids)) }
            }
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
        val previous = state.backStack.lastOrNull() ?: return false
        live.readingKey = null
        _state.update {
            it.copy(screen = previous, backStack = it.backStack.dropLast(1), notice = null)
        }
        store.saveScreen(Navigation.toJson(previous))
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
