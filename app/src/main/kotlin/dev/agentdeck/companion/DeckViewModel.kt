package dev.agentdeck.companion

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.claudeagents.core.mobile.MobileAttachment
import com.github.claudeagents.core.mobile.MobileAnswerRequest
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobilePairingPayload
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import com.github.claudeagents.core.mobile.MobileScheduleEditRequest
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileUsageReport
import com.github.claudeagents.core.mobile.MobileScheduledRow
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileStopRequest
import com.github.claudeagents.core.mobile.MobileToolResult
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.ApkInstall
import dev.agentdeck.companion.data.AppShortcuts
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.AppUpdate
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeDeliveryUncertain
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.OutgoingQueue
import dev.agentdeck.companion.data.OutgoingSend
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PendingPhoto
import dev.agentdeck.companion.data.PhotoEncoder
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
import kotlinx.coroutines.delay
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
    /**
     * The recency ordering held still under the reader's thumb ([FleetPins]). Not persisted:
     * a pin is about the list the reader is looking at *now*, and one restored from disk a day
     * later would rank today's fleet by yesterday's stamps.
     */
    val pins: dev.agentdeck.companion.data.FleetPins = dev.agentdeck.companion.data.FleetPins.NONE,
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
    /** The queued item whose bytes are on the wire right now, or null between attempts. */
    val delivering: String? = null,
    /**
     * Instructions this phone has taken responsibility for and not yet delivered. Loaded from
     * disk at startup, so one typed in a tunnel outlives the process Android reclaims there.
     */
    val outgoing: dev.agentdeck.companion.data.OutgoingQueue = dev.agentdeck.companion.data.OutgoingQueue(),
    val transcript: MobileTranscriptPage? = null,
    val transcriptLoading: Boolean = false,
    /**
     * A reload the *reader* asked for, by pulling the transcript down.
     *
     * Separate from [transcriptLoading] because the two paint different things: an unasked
     * reload gets the quiet bar over the turns already on screen, and a pulled one gets the
     * gesture's own spinner. Owned here rather than by the screen because only this side knows
     * whether the request actually started — a pull with no link never reaches the machine, and
     * a flag the screen set optimistically would have put the spinner over the *next*
     * automatic reload instead.
     */
    val transcriptRefreshing: Boolean = false,
    val earlierLoading: Boolean = false,
    val earlierError: String? = null,
    val earlierExpired: Boolean = false,
    val pendingTranscript: MobileTranscriptPage? = null,
    val findConversationKey: String? = null,
    /** The conversation whose outline sheet is open — key-scoped exactly as Find is. */
    val outlineConversationKey: String? = null,
    /** True while the open transcript is the cached copy rather than one this session fetched. */
    val transcriptCached: Boolean = false,
    val scheduled: List<MobileScheduledRow> = emptyList(),
    val scheduledLoading: Boolean = false,
    val scheduleEditId: String? = null,
    val scheduleEdit: MobileScheduleEditDetail? = null,
    val scheduleEditLoading: Boolean = false,
    val scheduleEditSaving: Boolean = false,
    val scheduleEditError: String? = null,
    /**
     * The whole body of one tool call, fetched only when the reader opens a result the page
     * had to cut. Keyed by call id so a sheet opened on a second call never paints the first
     * one's output while its own request is still in the air.
     */
    val toolOutputCallId: String? = null,
    val toolOutput: MobileToolResult? = null,
    val toolOutputLoading: Boolean = false,
    val toolOutputError: String? = null,
    /**
     * What the open conversation changed, and the one file whose diff is being read.
     *
     * Keyed by conversation like the transcript is: a review fetched for one chat must never
     * paint under another's title, and the tab is reachable from a row the user can leave at
     * any moment. [reviewPath] is the file open in the diff, null while the list is showing.
     */
    /** The conversation whose Changes tab is showing, or null while its messages are. */
    val changesOpenKey: String? = null,
    val review: MobileReviewList? = null,
    val reviewLoading: Boolean = false,
    val reviewError: String? = null,
    val reviewPath: String? = null,
    val reviewDiff: MobileReviewFileDiff? = null,
    val reviewDiffLoading: Boolean = false,
    val reviewDiffError: String? = null,
    val reviewMarking: Boolean = false,
    /**
     * `/v1/usage` — the machine's spend and plan windows, fetched when the screen opens.
     *
     * Kept after the screen closes on purpose: re-opening Usage should paint the last figures
     * immediately and refresh under them, because a blank page on a slow link reads as "this
     * machine has no usage" rather than as "still asking". Cleared on a machine switch with
     * every other machine-scoped field.
     */
    val usage: MobileUsageReport? = null,
    val usageLoading: Boolean = false,
    val usageError: String? = null,
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
    /** Conversations whose Stop is on the wire; the machine answers once it has checked the run. */
    val stopping: Set<String> = emptySet(),
    val snack: Snack? = null,
    val drafts: Map<String, String> = emptyMap(),
    /** A conversation's composer picks, by key — kept in memory like the New chat target. */
    val composerPicks: Map<String, ComposerPicks> = emptyMap(),
    /**
     * Photos already uploaded and waiting for the prompt that will name them, by draft key.
     *
     * In memory rather than in `SecureStore`, unlike the draft beside it, and the asymmetry is
     * the point: the *text* is the user's work and losing it is unforgivable, while the id is a
     * receipt for a file the machine will sweep in an hour anyway. Persisting it would mean an
     * app reopened tomorrow showing a chip for a photo the machine had already deleted.
     */
    val pendingPhotos: Map<String, List<PendingPhoto>> = emptyMap(),
    /** True while a pick is being re-encoded and uploaded; the attach button shows it. */
    val attaching: Boolean = false,
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

/**
 * What [key]'s composer sends next: each picked field, else what the desk would send next, else
 * defaults. Only a machine advertising `effort` vouches for the page's selection, and a cached
 * page's is as old as the cache, so neither is read.
 */
fun DeckState.composerSelection(key: String): MobileRunSelection {
    val none = MobileRunSelection(null, null, null)
    if (MobileProtocol.Capability.EFFORT !in hello?.capabilities.orEmpty()) return none
    val desk = transcript?.takeIf { it.key == key && !transcriptCached }?.selection ?: none
    return (composerPicks[key] ?: ComposerPicks()).over(desk)
}

/**
 * What a follow-up on [key] names on the wire, and which fields it leaves to the desk. A machine
 * advertising `send-desk-selection` is sent only what the reader picked and reads the rest off
 * the chat's own selectors as the send arrives — the page's copy is as old as its last reload, so
 * a model switched at the desk since would otherwise be overruled (t3code #5278). An older
 * machine gets the page's selection as before.
 */
fun DeckState.followUpSelection(key: String): Pair<MobileRunSelection, Set<MobileRunSelection.Field>> {
    if (MobileProtocol.Capability.SEND_DESK_SELECTION !in hello?.capabilities.orEmpty()) {
        return NewChat.forWire(hello, composerSelection(key)) to emptySet()
    }
    val picks = composerPicks[key] ?: ComposerPicks()
    return picks.over(MobileRunSelection(null, null, null)) to picks.unpicked()
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

    /** The single drain. One at a time is what keeps the queue's order the user's order. */
    private var drainJob: kotlinx.coroutines.Job? = null

    /**
     * Non-null while a drain owns the queue, and assigned **before** the coroutine starts.
     *
     * `viewModelScope` dispatches on `Main.immediate`, so `launch` runs its body inline from
     * the main thread: the very first thing the loop does is write the queue, that write wakes
     * the drain again, and `drainJob` is still the *previous* job at that moment. Guarding on
     * the job therefore let a second drain start on the same due item and deliver the user's
     * prompt twice.
     */
    private var drainToken: Any? = null

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
                outgoing = machine?.let { m -> restoredQueue(m.id) }
                    ?: dev.agentdeck.companion.data.OutgoingQueue(),
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
        drain()
        (_state.value.screen as? Screen.Conversation)?.let { openTranscript(it.key) }
        if (_state.value.screen is Screen.Scheduled) refreshScheduled()
        // The list lives in `Navigation.needsHello`, not here: a restore into a screen whose own
        // fetch is capability-gated declines that fetch against an empty capability list and
        // reports the machine has nothing (`memory/wiring.md`).
        if (Navigation.needsHello(_state.value.screen)) refreshHello()
    }

    /**
     * The screens read the connection through this, never directly: the link owns the socket
     * and the fleet, the view model owns what is on screen. Three collectors rather than one
     * combined flow because they arrive on their own schedules, and reading one flow's `.value`
     * inside another's collector inherits that one's emission times (`Memory.md`).
     */
    private fun observeLink() {
        viewModelScope.launch {
            live.fleet.collect { snapshot ->
                // Seen, then shown: a row's ranking key is taken from the first frame it
                // appears in, so the order the thumb is aiming at survives the next frame —
                // and the hold lasts exactly as long as the reader is on the list.
                _state.update {
                    it.copy(
                        snapshot = snapshot,
                        pins = it.pins.afterSnapshot(snapshot?.rows.orEmpty(), it.screen is Screen.Fleet),
                    )
                }
            }
        }
        viewModelScope.launch {
            live.link.collect { link ->
                val recovered = link == Link.Live && _state.value.link != Link.Live
                _state.update { it.copy(link = link, transcriptCached = it.transcriptCached ||
                    (link != Link.Live && it.transcript != null)) }
                if (recovered) {
                    // The link is what the queue was waiting for; a backoff that had climbed to
                    // five minutes must not outlive the outage it was measuring. Parked items
                    // stay parked — a reconnect is not a second opinion on a refusal, and an
                    // uncertain one is un-parked only once hello says the machine collapses a
                    // repeat, which `refreshHello` below is what learns.
                    _state.value.machine?.id?.let { id ->
                        writeQueue(id, OutgoingQueue(_state.value.outgoing.items.map {
                            if (it.parked) it else OutgoingQueue.resumed(it)
                        }))
                    }
                    refreshHello()
                    (_state.value.screen as? Screen.Conversation)?.let { loadTranscript(it.key, quiet = true) }
                    if (_state.value.screen == Screen.Scheduled) refreshScheduled()
                    // Re-asked like the transcript and the schedule beside it: a page whose one
                    // fetch failed in the outage would otherwise sit on its error until the
                    // reader navigated away and back.
                    if (_state.value.screen == Screen.Usage) loadUsage()
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

    /**
     * Test seam. The link owns the socket and builds it from a paired machine's address, which
     * a JVM test has none of; the queue's composition — which write wakes the drain, and in what
     * order it delivers — is exactly what cannot be proven without one.
     */
    internal var connectionForTest: (() -> BridgeClient?)? = null

    private fun client(): BridgeClient? = connectionForTest?.invoke() ?: live.client()

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
                _state.update { it.copy(pairing = false, pairError = pairPinSentence(error) ?: describe(error)) }
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
    /**
     * The queue as it must be read back: an item that was on the wire when this app last
     * stopped parks and asks, because nothing recorded whether the machine ran it.
     */
    private fun restoredQueue(machineId: String): OutgoingQueue =
        OutgoingQueue.restored(store.outgoing(machineId), OutgoingQueue.INTERRUPTED)
            .also { store.saveOutgoing(machineId, it) }

    private fun switchTo(machine: PairedMachine?) {
        val positions = machine?.let { store.readingPositions(it.id) } ?: dev.agentdeck.companion.data.ReadingPositions()
        earlierRequest++
        machineGeneration++
        transcriptRequests.reset()
        drainJob?.cancel()
        drainToken = null
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
                // The queue belongs to the machine it is addressed to, so switching pairings
                // swaps it wholesale. A prompt aimed at the laptop must never be delivered to
                // the desktop merely because the user looked at the desktop first.
                outgoing = machine?.let { m -> restoredQueue(m.id) }
                    ?: dev.agentdeck.companion.data.OutgoingQueue(),
                    delivering = null,
                composerPicks = emptyMap(),
                // Machine-scoped like the queue beside it: an attachment id names a file on the
                // machine that minted it, so carrying one to the next machine sends an id that
                // was never there and reports it as expired.
                pendingPhotos = emptyMap(),
                // Machine-scoped for the same reason: a plan window belongs to one machine's
                // accounts, and the laptop's 94% painted under the desktop's name is a number
                // the reader would act on.
                usage = null,
                usageLoading = false,
                usageError = null,
                transcript = null,
                transcriptLoading = false,
                transcriptRefreshing = false,
                transcriptCached = false,
                earlierLoading = false, earlierError = null, earlierExpired = false,
                pendingTranscript = null, findConversationKey = null,
                scheduled = emptyList(),
                scheduleEditId = null,
                scheduleEdit = null,
                scheduleEditLoading = false,
                scheduleEditSaving = false,
                scheduleEditError = null,
                hello = null,
                snoozed = emptyMap(),
                notice = null,
            )
        }
        store.saveScreen(machine?.let { Navigation.toJson(Screen.Fleet) })
        StreamService.reconcile(getApplication(), _state.value.settings.stayConnected && machine != null)
        if (machine != null) {
            live.refresh(initial = true)
            // The queue that just came in belongs to this machine and may already be due: the
            // drain was cancelled above, and nothing else restarts it while the link is already
            // live, so a switch used to strand whatever the other machine was still owed.
            drain()
        }
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
                    // The same shape, for the same reason: the Usage screen asks once when it
                    // composes, and that ask is gated on a capability it may not have heard of
                    // yet — on a restore, a deep link, or the first hello after a reconnect. The
                    // gate turning true is the moment nothing else would re-drive the fetch.
                    val discoveredUsage = MobileProtocol.Capability.USAGE in hello.capabilities &&
                        MobileProtocol.Capability.USAGE !in _state.value.hello?.capabilities.orEmpty()
                    _state.update { it.copy(hello = hello) }
                    if (discoveredPaging) (_state.value.screen as? Screen.Conversation)?.key?.let {
                        loadTranscript(it, quiet = true)
                    }
                    if (discoveredUsage && _state.value.screen == Screen.Usage) loadUsage()
                    // This is where a machine *promises* to recognise a repeat. Until it has,
                    // an item parked for uncertain delivery is the user's decision; after it,
                    // the app may take it back itself — which is the whole of `send-dedupe`.
                    if (MobileProtocol.Capability.SEND_DEDUPE in hello.capabilities) {
                        _state.value.machine?.id?.let { id ->
                            writeQueue(id, OutgoingQueue.resumable(_state.value.outgoing))
                        }
                    }
                    // A phone paired before the relay had a grant holds LAN addresses only, and
                    // without this nothing ever tells it where else the machine answers.
                    if (MobileProtocol.Capability.HOSTS in hello.capabilities) adoptMachineHosts(client)
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

    private suspend fun adoptMachineHosts(client: BridgeClient) {
        val advertised = withContext(Dispatchers.IO) { runCatching { client.hosts() } }
            .onFailure { Log.i(TAG, "The machine did not answer /v1/hosts", it) }
            .getOrNull()?.hosts ?: return
        val updated = live.adoptHosts(advertised) ?: return
        _state.update { state ->
            if (state.machine?.id == updated.id) state.copy(machine = updated) else state
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

    /**
     * Asking for a fresh answer releases the ranking hold: at the moment of the pull nothing is
     * being aimed at, and the true order is exactly what the gesture asked for.
     */
    fun refreshFleet(initial: Boolean = false) {
        _state.update { it.copy(pins = it.pins.released()) }
        live.refresh(initial)
    }

    fun setFilter(filter: FleetFilter) {
        _state.update { it.copy(filter = filter) }
        updateBrowsing { it.copy(filter = filter, anchor = dev.agentdeck.companion.data.ReadingAnchor(followingLatest = false)) }
    }

    fun setSort(sort: FleetSort) {
        // A new ranking is the user asking a different question; holding the old one's keys
        // over it would answer neither.
        _state.update { it.copy(sort = sort, pins = it.pins.released()) }
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
    fun openNewChat(prompt: String? = null) {
        push(Screen.NewChat)
        consumeShare(NEW_CHAT_DRAFT_KEY)
        // Appended, never assigned, for the same reason a share is (`consumeShare`): the New
        // chat draft may already hold a half-written question, and this app's one promise
        // about drafts is that it keeps them.
        prompt?.takeIf { it.isNotBlank() }?.let { text ->
            setDraft(NEW_CHAT_DRAFT_KEY, Sharing.appendedTo(_state.value.drafts[NEW_CHAT_DRAFT_KEY].orEmpty(), text))
        }
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
        val state = _state.value
        val machineId = state.machine?.id ?: return
        val target = state.newChatTarget ?: return
        val accountId = NewChat.accountFor(state.hello, target)
        val picks = NewChat.forWire(state.hello, target.selection)
        val prompt = state.drafts[NEW_CHAT_DRAFT_KEY].orEmpty()
        val photos = state.pendingPhotos[NEW_CHAT_DRAFT_KEY].orEmpty()
        if (prompt.isBlank() && photos.isEmpty()) return
        if (isBeingDelivered(null, prompt, photos)) return
        _state.update { it.copy(notice = null) }
        enqueue(
            machineId,
            OutgoingSend(
                clientMessageId = java.util.UUID.randomUUID().toString(),
                key = null,
                projectPath = target.projectPath,
                vendor = target.vendor,
                label = target.projectPath.substringAfterLast('/'),
                prompt = prompt,
                accountId = accountId,
                // Null is the machine's own default — the field is then absent from the
                // request, which is what this screen always sent.
                model = picks.model,
                effort = picks.effort,
                permissionMode = picks.permissionMode,
                attachmentIds = photos.map { it.attachmentId },
            ),
        )
        clearSentDraft(NEW_CHAT_DRAFT_KEY, prompt)
        clearPhotos(NEW_CHAT_DRAFT_KEY)
        // New chat has no queue chip — it is not a conversation yet — so a start the link
        // cannot carry would otherwise clear the composer and show nothing at all.
        if (_state.value.link != Link.Live) snack("Queued · waiting for the machine")
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
            // Pushed, not gone-to: Usage is a page of Settings and Back belongs on it. A link
            // that arrives while Settings is not open still lands with Settings underneath,
            // because `Navigation.restoredBackStack` names its parent.
            DeepLink.Usage -> openUsage()
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

    /** The `notice` a failed conversation read put up, so the next good read can take it down again. */
    private var readFailureNotice: String? = null

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
                findConversationKey = null, outlineConversationKey = null,
                changesOpenKey = null, review = null, reviewError = null,
                reviewPath = null, reviewDiff = null, reviewDiffError = null)
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

    fun loadTranscript(key: String, quiet: Boolean = false, pulled: Boolean = false) {
        if ((_state.value.screen as? Screen.Conversation)?.key != key) return
        val client = client() ?: return
        val machineId = _state.value.machine?.id ?: return
        val generation = machineGeneration
        val request = transcriptRequests.begin(machineId, key) ?: return
        val force = forceTranscriptKey == key
        val paging = MobileProtocol.Capability.TRANSCRIPT_PAGING in _state.value.hello?.capabilities.orEmpty()
        if (!quiet) _state.update { it.copy(transcriptLoading = true) }
        // Past every guard: the spinner belongs to a request that is really on the wire.
        if (pulled) _state.update { it.copy(transcriptRefreshing = true) }
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
                clearReadFailureNotice()
                val current = _state.value
                val old = current.transcript
                val following = current.readingPositions.conversations[key]?.followingLatest != false
                val retained = if (force) page else dev.agentdeck.companion.data.TranscriptPages.refresh(old, page, following)
                if (retained != null) {
                    if (force) forceTranscriptKey = null
                    _state.update { it.copy(transcript = retained, transcriptLoading = false,
                        transcriptRefreshing = false,
                        transcriptCached = false, pendingTranscript = null,
                        earlierError = if (force) null else it.earlierError,
                        earlierExpired = if (force) false else it.earlierExpired) }
                    cacheDisplayedTranscript(machineId, generation, retained)
                    if (retained.historyPending && !retained.historyPaused) loadEarlier(key, automatic = true)
                } else {
                    _state.update { it.copy(transcriptLoading = false, transcriptRefreshing = false,
                        pendingTranscript = page) }
                }
            }.onFailure { error ->
                _state.update { it.copy(transcriptLoading = false, transcriptRefreshing = false,
                    earlierError = if (force) describe(error) else it.earlierError,
                    earlierExpired = if (force) true else it.earlierExpired) }
                // One read that fails on a slow link says nothing about the stream, which has its
                // own watchdog: only a pin or revocation verdict may move the link banner.
                if (!force) {
                    if (fatal(error)) live.failLink(error) else showReadFailure(describe(error))
                }
            }
            if (refreshAgain) loadTranscript(key, quiet = true)
        }
    }

    private fun showReadFailure(message: String) {
        readFailureNotice = message
        _state.update { it.copy(notice = message) }
    }

    private fun clearReadFailureNotice() {
        val shown = readFailureNotice ?: return
        readFailureNotice = null
        _state.update { if (it.notice == shown) it.copy(notice = null) else it }
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

    fun outlineConversation(open: Boolean) {
        _state.update { it.copy(outlineConversationKey = if (open) (it.screen as? Screen.Conversation)?.key else null) }
    }

    fun acceptLatest(key: String) {
        val page = _state.value.pendingTranscript?.takeIf { it.key == key } ?: return
        earlierRequest++
        _state.update { it.copy(transcript = page, pendingTranscript = null, transcriptCached = false,
            earlierLoading = false, earlierError = null, earlierExpired = false) }
        val machineId = _state.value.machine?.id ?: return
        cacheDisplayedTranscript(machineId, machineGeneration, page)
    }

    /**
     * The pull gesture: re-ask the machine for this conversation and **take** what it sends.
     *
     * `forceTranscriptKey` is what makes it take: without it the continuity merge parks a page
     * that differs from the one on screen behind the jump-to-latest, which is right for a
     * reload nobody asked for and wrong for one the reader just pulled for — the spinner would
     * run and the screen would not move, which reads exactly like a dead link. The merged
     * earlier pages go with it, as they do on any forced read; "Load earlier messages" is still
     * at the top.
     */
    fun refreshTranscript(key: String) {
        if ((_state.value.screen as? Screen.Conversation)?.key != key) return
        earlierRequest++
        _state.update { it.copy(earlierError = null, earlierExpired = false, earlierLoading = false) }
        transcriptRequests.reset()
        forceTranscriptKey = key
        loadTranscript(key, quiet = true, pulled = true)
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

    fun setComposerPick(key: String, field: ComposerPicks.Field, value: String?) = _state.update {
        it.copy(composerPicks = it.composerPicks + (key to (it.composerPicks[key] ?: ComposerPicks()).with(field, value)))
    }

    /** A landed send made its picks the desk's own, so the composer follows the desk again (t3code #10202). */
    private fun forgetSentPicks(key: String, sent: MobileRunSelection) = _state.update { state ->
        val rest = state.composerPicks[key]?.without(sent) ?: return@update state
        state.copy(composerPicks = if (rest.picked.isEmpty()) state.composerPicks - key else state.composerPicks + (key to rest))
    }

    /**
     * Takes responsibility for the instruction, then tries to deliver it.
     *
     * The order is the point. The prompt is written to the durable queue *before* the composer
     * clears, so an app killed between the tap and the ack still owes the machine the sentence
     * the user typed — which is exactly the moment the link is down and the queue is the only
     * copy. A send that does not land is therefore never lost and never silently repeated: it
     * waits, retries on a backoff while it plainly never reached the machine, and parks for the
     * user the moment repeating it might run the prompt twice.
     */
    fun send(target: Screen.Conversation, prompt: String, stopFirst: Boolean) {
        val machineId = _state.value.machine?.id ?: return
        val photos = _state.value.pendingPhotos[target.key].orEmpty()
        // A photo alone is a message, as it is on the desktop — so "nothing typed", the one
        // decline W5 allows, now means nothing typed *and* nothing attached. Leaving this at
        // the old rule made the Send button enabled over a photo and dead when tapped.
        if (prompt.isBlank() && photos.isEmpty()) return
        val (picks, deskFields) = _state.value.followUpSelection(target.key)
        // A double tap on the *same* send still collapses. Only that one: a prompt merely
        // queued or parked is not a reason to decline a second instruction, and declining one
        // silently is the refusal `RULES.md` W5 forbids.
        if (isBeingDelivered(target.key, prompt, photos)) return
        _state.update { it.copy(notice = null) }
        enqueue(
            machineId,
            OutgoingSend(
                clientMessageId = java.util.UUID.randomUUID().toString(),
                key = target.key,
                projectPath = target.projectPath,
                vendor = target.vendor,
                label = target.title,
                prompt = prompt,
                model = picks.model,
                effort = picks.effort,
                permissionMode = picks.permissionMode,
                deskFields = deskFields,
                attachmentIds = photos.map { it.attachmentId },
                stopFirst = stopFirst,
            ),
        )
        clearSentDraft(target.key, prompt)
        clearPhotos(target.key)
    }

    // ---- the composer's photos and @-mentions ------------------------------------------

    /**
     * Re-encodes a picked photo and uploads it, then shows a chip. Off the main thread for both
     * halves — a 12-megapixel decode on the UI thread is a visible freeze.
     *
     * Every failure ends in a sentence, never in silence: a photo the user watched the app
     * accept and then quietly dropped is worse than one that was refused out loud.
     */
    fun attachPhoto(draftKey: String, source: ByteArray) {
        val client = client() ?: return
        // Before the upload, not after it: accepting the bytes and then dropping the receipt
        // spends the machine's quota and disk on a photo the reader never sees a chip for.
        if (_state.value.pendingPhotos[draftKey].orEmpty().size >= MobileAttachment.MAX_PER_SEND) {
            return snack("Up to ${MobileAttachment.MAX_PER_SEND} photos per message")
        }
        _state.update { it.copy(attaching = true) }
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.Default) { PhotoEncoder.encode(source) }
            if (encoded == null) {
                _state.update { it.copy(attaching = false) }
                // Both numbers, because "too large" alone reads as a bug when the user can see
                // the phone shrank it: the sentence has to say what it got down to and the bar.
                snack(
                    "That photo could not be shrunk below " +
                        (MobileAttachment.MAX_ATTACH_BYTES / (1024 * 1024)) + " MB (it is " +
                        PhotoEncoder.label(source.size).removePrefix("Photo · ") + ")",
                )
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) { runCatching { client.attach(encoded) } }
            _state.update { it.copy(attaching = false) }
            outcome.onSuccess { accepted ->
                val photo = PendingPhoto(accepted.attachmentId, PhotoEncoder.label(encoded.size))
                _state.update { state ->
                    val existing = state.pendingPhotos[draftKey].orEmpty()
                    state.copy(pendingPhotos = state.pendingPhotos + (draftKey to existing + photo))
                }
            }.onFailure { error -> snack(describe(error)) }
        }
    }

    fun removePhoto(draftKey: String, attachmentId: String) = _state.update { state ->
        val left = state.pendingPhotos[draftKey].orEmpty().filterNot { it.attachmentId == attachmentId }
        state.copy(
            pendingPhotos = if (left.isEmpty()) state.pendingPhotos - draftKey
            else state.pendingPhotos + (draftKey to left),
        )
    }

    private fun clearPhotos(draftKey: String) =
        _state.update { it.copy(pendingPhotos = it.pendingPhotos - draftKey) }

    /**
     * The `@` popup's rows. Null is the machine saying "the index is not ready" — a different
     * answer from an empty list, and the popup renders it as its own line rather than as "no
     * such file" (`core/mobile/MobileFiles.kt`).
     */
    suspend fun searchFiles(projectPath: String, query: String): List<String>? {
        val client = client() ?: return null
        return withContext(Dispatchers.IO) {
            // A dropped link and "the index is not ready" are not the same sentence, and the
            // popup renders null as the second one: an unreachable machine answers with no rows
            // rather than with a claim about its index.
            val answer = runCatching { client.files(projectPath, query) }.getOrNull() ?: return@withContext emptyList()
            if (answer.indexing) null else answer.paths
        }
    }

    // ---- the outgoing queue ------------------------------------------------------------

    /**
     * True only for the send whose bytes are on the wire right now.
     *
     * The photos are part of the identity: before M6 two sends with the same text really were
     * the same message, and comparing text alone now drops a second send that differs only in
     * what it attached — the silent decline W5 forbids.
     */
    private fun isBeingDelivered(key: String?, prompt: String, photos: List<PendingPhoto>): Boolean {
        val id = _state.value.delivering ?: return false
        val item = _state.value.outgoing.find(id) ?: return false
        return item.key == key && item.prompt == prompt &&
            item.attachmentIds == photos.map { it.attachmentId }
    }

    private fun enqueue(machineId: String, item: OutgoingSend) {
        writeQueue(machineId, _state.value.outgoing.with(item))
    }

    /**
     * Every write to the queue goes through here, and every write wakes the drain.
     *
     * The wake is not decoration: the head of a lane blocks the items behind it, so *removing*
     * a parked head — Edit or Discard — is exactly as much a reason to try again as adding a
     * row is. Without it, discarding a refused prompt left the one typed after it sitting in
     * the file until the next send, the next reconnect or the next launch.
     */
    private fun writeQueue(machineId: String, queue: OutgoingQueue) {
        _state.update { it.copy(outgoing = queue) }
        store.saveOutgoing(machineId, queue)
        drain()
    }

    /** The user's own Retry on a parked item: it goes back in the running, now. */
    fun retryQueued(id: String) {
        val machineId = _state.value.machine?.id ?: return
        val item = _state.value.outgoing.find(id) ?: return
        writeQueue(machineId, _state.value.outgoing.with(OutgoingQueue.resumed(item)))
    }

    /** Puts a parked prompt back in the composer it came from and stops owing it. */
    fun editQueued(id: String) {
        val machineId = _state.value.machine?.id ?: return
        val item = _state.value.outgoing.find(id) ?: return
        val draftKey = item.key ?: NEW_CHAT_DRAFT_KEY
        setDraft(draftKey, Sharing.appendedTo(draft(draftKey), item.prompt))
        // The photos come back with the text. They are already uploaded and still named by this
        // item, so dropping them here would re-send the prompt without what it was about and
        // leave the files on the machine until they expire.
        if (item.attachmentIds.isNotEmpty()) {
            _state.update { state ->
                val existing = state.pendingPhotos[draftKey].orEmpty()
                val restored = item.attachmentIds
                    .filterNot { restoredId -> existing.any { it.attachmentId == restoredId } }
                    .map { PendingPhoto(it, "Photo") }
                state.copy(pendingPhotos = state.pendingPhotos + (draftKey to existing + restored))
            }
        }
        writeQueue(machineId, _state.value.outgoing.without(id))
    }

    fun discardQueued(id: String) {
        val machineId = _state.value.machine?.id ?: return
        writeQueue(machineId, _state.value.outgoing.without(id))
        snack("Discarded")
    }

    /**
     * One delivery at a time, oldest first, for as long as something is due.
     *
     * Serial rather than parallel because order is the user's: two instructions typed into one
     * conversation must reach the agent in the order they were written, and a parallel drain
     * decides that by whichever socket wins.
     */
    private fun drain() {
        if (drainToken != null) return
        val token = Any()
        drainToken = token
        drainJob = viewModelScope.launch {
            try {
            while (true) {
                val machineId = _state.value.machine?.id ?: return@launch
                val generation = machineGeneration
                val wait = _state.value.outgoing.nextWakeMs(System.currentTimeMillis()) ?: return@launch
                if (wait > 0) delay(wait)
                if (generation != machineGeneration) return@launch
                val item = _state.value.outgoing.due(System.currentTimeMillis()) ?: return@launch
                // No connection to hand it to. Returning ends the drain rather than looping:
                // `deliver` would change nothing, so the loop would spin on a due item forever.
                val client = client() ?: return@launch
                deliver(client, machineId, generation, item)
            }
            } finally {
                // Only if this drain still owns the flag: a machine switch cancels one and
                // starts the next, and a late `finally` must not unlock the new one.
                if (drainToken === token) drainToken = null
            }
        }
    }

    private suspend fun deliver(
        client: BridgeClient,
        machineId: String,
        generation: Long,
        item: OutgoingSend,
    ) {
        val dedupes = MobileProtocol.Capability.SEND_DEDUPE in _state.value.hello?.capabilities.orEmpty()
        // Written to the file *before* the bytes go out, so a process death here leaves
        // evidence that an attempt began. Without it the row reads as untried on the next
        // start and is repeated automatically — the prompt run twice, which is the one outcome
        // the uncertain-delivery rule exists to prevent.
        writeQueue(machineId, _state.value.outgoing.with(OutgoingQueue.attempting(item)))
        _state.update { it.copy(delivering = item.clientMessageId) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                // The interrupt rides the *first* attempt only. A retry after a lost ack would
                // otherwise stop the run the first attempt had just started, and then report
                // success off the machine's memoised acceptance. Its own failure is not the
                // send's: a Stop the machine would not take is no reason to withhold the prompt
                // (`RULES.md` W5).
                if (item.stopFirst && item.key != null && item.attempts == 0) {
                    runCatching { client.stop(MobileStopRequest(item.key)) }
                }
                client.send(item.request())
            }
        }
        _state.update { it.copy(delivering = null) }
        if (generation != machineGeneration || _state.value.machine?.id != machineId) return
        // Discarded or edited while it was on the wire: the user has said they no longer own
        // this instruction, and writing the outcome back would put the prompt they dismissed
        // into the queue again.
        if (_state.value.outgoing.find(item.clientMessageId) == null) return
        outcome.onSuccess { accepted ->
            writeQueue(machineId, _state.value.outgoing.without(item.clientMessageId))
            if (item.key == null) {
                if (_state.value.screen == Screen.NewChat) go(Screen.Fleet)
                snack(accepted.notice ?: "Started on ${item.projectPath.substringAfterLast('/')}")
                refreshFleet()
            } else {
                // Only a machine that writes the picks onto the chat's selectors carries them on:
                // an older one would leave the desk's old model to run the next turn.
                if (MobileProtocol.Capability.SEND_SELECTORS in _state.value.hello?.capabilities.orEmpty()) {
                    forgetSentPicks(item.key, MobileRunSelection(item.model, item.effort, item.permissionMode))
                }
                if ((_state.value.screen as? Screen.Conversation)?.key == item.key) {
                    loadTranscript(item.key, quiet = true)
                }
                snack(accepted.notice
                    ?: "Sent to ${item.vendor.name.lowercase().replaceFirstChar(Char::uppercase)}")
            }
        }.onFailure { error ->
            val refusal = error as? BridgeRefusal
            val parked = OutgoingQueue.afterFailure(
                item = item,
                nowMs = System.currentTimeMillis(),
                // The machine's own sentence where it authored one; otherwise this app's, which
                // never claims to know what the other end did.
                error = refusal?.message ?: describeSend(error),
                refused = refusal != null,
                reachedMachine = error is BridgeDeliveryUncertain,
                dedupes = dedupes,
            )
            writeQueue(machineId, _state.value.outgoing.with(parked))
            if (parked.parked) {
                // `notice` is painted by the conversation and the New chat composer and by
                // nothing else, so it is used only when the reader is looking at the one this
                // send belongs to. Anywhere else the sentence has to be a snack, or it waits
                // invisibly and then appears over an unrelated chat.
                val looking = when {
                    item.key != null -> (_state.value.screen as? Screen.Conversation)?.key == item.key
                    else -> _state.value.screen == Screen.NewChat
                }
                if (looking) _state.update { it.copy(notice = parked.lastError) }
                else snack(parked.lastError.orEmpty())
            }
            if (fatal(error)) live.failLink(error)
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
        if (key in _state.value.stopping) return
        val generation = machineGeneration
        _state.update { it.copy(stopping = it.stopping + key, notice = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.stop(MobileStopRequest(key)) }
            }
            _state.update { it.copy(stopping = it.stopping - key) }
            if (generation != machineGeneration) return@launch
            outcome
                .onSuccess { announce?.let(::snack) }
                .onFailure { error -> _state.update { it.copy(notice = describe(error)) } }
            loadTranscript(key, quiet = true)
            refreshFleet()
        }
    }

    // ---- usage -----------------------------------------------------------------------

    private var usageGeneration = 0L

    /** Opens the Usage page. The screen asks for the figures itself, once, when it composes. */
    fun openUsage() = push(Screen.Usage)

    /**
     * Loads the machine's spend and plan windows.
     *
     * Asked for when the reader opens the screen and not before: the machine walks every
     * indexed conversation to answer, and most sessions never open Usage. Its own generation
     * like every other keyed fetch, so a machine switched under a slow reply never paints the
     * old machine's spend under the new machine's name.
     */
    fun loadUsage() {
        val client = client() ?: return
        if (MobileProtocol.Capability.USAGE !in _state.value.hello?.capabilities.orEmpty()) return
        val generation = machineGeneration
        val own = ++usageGeneration
        _state.update { it.copy(usageLoading = true, usageError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.usage() } }
            if (generation != machineGeneration || own != usageGeneration) return@launch
            outcome.onSuccess { report ->
                _state.update { it.copy(usage = report, usageLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(usageLoading = false, usageError = describe(error)) }
            }
        }
    }

    // ---- review ----------------------------------------------------------------------

    private var reviewGeneration = 0L

    /**
     * Moves between a conversation's two halves. A pure setter on purpose: the screen's own
     * "load it if I have none" guard is the single owner of the fetch, and a second guard here
     * read *this* state while the screen read its last composition's — so the first tap on
     * Changes spent two transcript scans and threw the first away.
     */
    fun showChanges(key: String, open: Boolean) {
        _state.update { it.copy(changesOpenKey = key.takeIf { _ -> open }) }
    }

    /**
     * Loads what the open conversation changed.
     *
     * Asked for when the reader opens the Changes tab and not before: the list costs the
     * machine a transcript scan and every changed file's baseline, and most conversations are
     * read without anybody looking at a diff.
     */
    fun loadReview(key: String) {
        val client = client() ?: return
        val generation = machineGeneration
        val own = ++reviewGeneration
        _state.update { it.copy(reviewLoading = true, reviewError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.review(key) } }
            if (generation != machineGeneration || own != reviewGeneration) return@launch
            if ((_state.value.screen as? Screen.Conversation)?.key != key) return@launch
            outcome.onSuccess { list ->
                _state.update { it.copy(review = list, reviewLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(reviewLoading = false, reviewError = describe(error)) }
            }
        }
    }

    /** Opens one file's diff. Its own generation, so a second tap never paints under the first. */
    fun openReviewFile(key: String, path: String) {
        val client = client() ?: return
        val generation = machineGeneration
        val own = ++reviewGeneration
        _state.update {
            it.copy(reviewPath = path, reviewDiff = null, reviewDiffLoading = true, reviewDiffError = null)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.reviewFile(key, path) } }
            if (generation != machineGeneration || own != reviewGeneration) return@launch
            if (_state.value.reviewPath != path) return@launch
            outcome.onSuccess { diff ->
                _state.update { it.copy(reviewDiff = diff, reviewDiffLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(reviewDiffLoading = false, reviewDiffError = describe(error)) }
            }
        }
    }

    /** Back from a file to the list; the fetched diff is dropped, the list is not re-fetched. */
    fun closeReviewFile() {
        ++reviewGeneration
        _state.update {
            it.copy(reviewPath = null, reviewDiff = null, reviewDiffLoading = false, reviewDiffError = null)
        }
    }

    /**
     * Ticks files on the machine's own checklist. Empty [paths] means every changed file.
     *
     * The reply carries which ticks are now set, so the list is updated from the machine's
     * answer rather than from what the tap assumed — a refusal must not leave a checkbox on.
     */
    fun markReviewed(key: String, paths: List<String>, reviewed: Boolean = true) {
        val client = client() ?: return
        val generation = machineGeneration
        _state.update { it.copy(reviewMarking = true, reviewError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.markReviewed(key, paths, reviewed) }
            }
            if (generation != machineGeneration) return@launch
            outcome.onSuccess { marked ->
                _state.update { state ->
                    // Only into the list this receipt is *about*. Two conversations of one repo
                    // share file paths, so a slow reply merged into whichever list is open now
                    // would tick rows the machine never set — the same identity re-check
                    // `loadReview` and `openReviewFile` make after their own IO hop.
                    val current = state.review?.takeIf { it.key == key } ?: return@update state.copy(
                        reviewMarking = false,
                    )
                    val ticks = marked.files.associate { it.path to it.reviewed }
                    val files = current.files.map { it.copy(reviewed = ticks[it.path] ?: it.reviewed) }
                    state.copy(
                        reviewMarking = false,
                        // The receipt carries ticks, not counts: the rows keep the sizes the
                        // list route measured rather than losing them to a write's reply.
                        review = current.copy(files = files, reviewedFiles = files.count { it.reviewed }),
                    )
                }
                refreshFleet()
            }.onFailure { error ->
                // Spoken, not only stored: this write is reachable from a fleet row's sheet,
                // where `reviewError` is never painted — a refused tick there used to leave the
                // row exactly as it was and say nothing at all.
                _state.update { it.copy(reviewMarking = false, reviewError = describe(error)) }
                snack(describe(error))
            }
        }
    }

    // ---- markdown images -------------------------------------------------------------

    private val markdownImages = dev.agentdeck.companion.data.MarkdownImages<ImageBitmap>()

    /**
     * One picture of a conversation's markdown, or null where the machine will not show it — the
     * bubble then keeps its one-line note. A machine change drops the cache with the client.
     */
    suspend fun conversationImage(key: String, src: String): ImageBitmap? {
        val client = client() ?: return null
        val scope = "${_state.value.machine?.id}|$key|$src"
        return markdownImages.load(scope) {
            withContext(Dispatchers.IO) {
                val bytes = client.image(key, src)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }

    // ---- tool output -----------------------------------------------------------------

    private var toolOutputGeneration = 0L

    /**
     * Asks the machine for the rest of a tool call's output.
     *
     * The page already carries the head and the tail of every call, so this runs only when the
     * reader opened the one result that was cut — never on opening the sheet, and never for a
     * body that arrived whole.
     */
    fun loadToolOutput(key: String, callId: String) {
        val client = client() ?: return
        val generation = machineGeneration
        val outputGeneration = ++toolOutputGeneration
        _state.update {
            it.copy(toolOutputCallId = callId, toolOutput = null,
                toolOutputLoading = true, toolOutputError = null)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.toolResult(key, callId) } }
            if (generation != machineGeneration || outputGeneration != toolOutputGeneration) return@launch
            outcome.onSuccess { result ->
                _state.update { it.copy(toolOutput = result, toolOutputLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(toolOutputLoading = false, toolOutputError = describe(error)) }
            }
        }
    }

    /** Closing the sheet drops the fetched body: the page's own copy is what reopens it. */
    fun clearToolOutput() {
        ++toolOutputGeneration
        _state.update {
            it.copy(toolOutputCallId = null, toolOutput = null,
                toolOutputLoading = false, toolOutputError = null)
        }
    }

    // ---- scheduled -------------------------------------------------------------------

    private var scheduleEditGeneration = 0L

    fun openScheduleEditor(id: String) {
        val client = client() ?: return
        val generation = machineGeneration
        val editGeneration = ++scheduleEditGeneration
        val hello = _state.value.hello
        if (hello != null && MobileProtocol.Capability.SCHEDULE_EDIT !in hello.capabilities) {
            _state.update { it.copy(scheduleEditId = id, scheduleEdit = null, scheduleEditLoading = false,
                scheduleEditError = "Update the IDE plugin on this machine to edit scheduled prompts.") }
            return
        }
        _state.update { it.copy(scheduleEditId = id, scheduleEdit = null,
            scheduleEditLoading = true, scheduleEditSaving = false, scheduleEditError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.scheduleEdit(id) } }
            if (generation != machineGeneration || editGeneration != scheduleEditGeneration) return@launch
            outcome.onSuccess { detail ->
                _state.update { it.copy(scheduleEdit = detail, scheduleEditLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(scheduleEditLoading = false, scheduleEditError = describe(error)) }
            }
        }
    }

    fun closeScheduleEditor() {
        if (_state.value.scheduleEditSaving) return
        ++scheduleEditGeneration
        _state.update { it.copy(scheduleEditId = null, scheduleEdit = null,
            scheduleEditError = null, scheduleEditLoading = false) }
    }

    fun saveScheduleEdit(request: MobileScheduleEditRequest) {
        val state = _state.value
        val id = state.scheduleEditId ?: return
        if (state.scheduleEditSaving) return
        val client = client() ?: return
        val generation = machineGeneration
        val editGeneration = scheduleEditGeneration
        val draftKey = scheduleEditDraftKey(id)
        val sentDraft = state.drafts[draftKey].orEmpty()
        _state.update { it.copy(scheduleEditSaving = true, scheduleEditError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.saveScheduleEdit(id, request) } }
            if (generation != machineGeneration || editGeneration != scheduleEditGeneration) return@launch
            outcome.onSuccess {
                if (draft(draftKey) == sentDraft) setDraft(draftKey, "")
                _state.update { it.copy(scheduleEditSaving = false) }
                closeScheduleEditor()
                snack("Schedule saved")
                refreshScheduled()
            }.onFailure { error ->
                _state.update { it.copy(scheduleEditSaving = false, scheduleEditError = describe(error)) }
            }
        }
    }

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
    fun createSchedule(target: NewChatTarget, dueAtMs: Long) {
        val client = client() ?: return
        val generation = machineGeneration
        val accountId = NewChat.accountFor(_state.value.hello, target)
        val picks = NewChat.forWire(_state.value.hello, target.selection)
        val prompt = _state.value.drafts[SCHEDULE_DRAFT_KEY].orEmpty()
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    client.send(
                        MobileSendRequest(
                            key = null,
                            projectPath = target.projectPath,
                            prompt = prompt,
                            vendor = target.vendor,
                            model = picks.model,
                            effort = picks.effort,
                            permissionMode = picks.permissionMode,
                            newChat = true,
                            dueAtMs = dueAtMs,
                            accountId = accountId,
                        ),
                    )
                }
            }
            if (generation != machineGeneration) return@launch
            outcome.onSuccess { accepted ->
                // Consumed, so the draft goes — the rule is that unsent text survives, and
                // this one is no longer unsent.
                clearSentDraft(SCHEDULE_DRAFT_KEY, prompt)
                snack(
                    accepted.notice ?: ("Scheduled for " +
                        dev.agentdeck.companion.ui.Times.clock(dueAtMs, System.currentTimeMillis())),
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

        /** While pairing there is no earlier pairing to redo: a pin mismatch is the fingerprint entered. */
        internal fun pairPinSentence(error: Throwable): String? =
            if (error is PinMismatchException) {
                "The fingerprint does not match this machine. Check it against the one shown in the IDE."
            } else {
                null
            }

        /** The Schedule dialog's prompt, kept across close/reopen like every other field. */
        const val SCHEDULE_DRAFT_KEY = "scheduled-prompt"
        fun scheduleEditDraftKey(id: String) = "scheduled-edit:$id"
    }
}
