package dev.agentdeck.companion

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.Snooze
import dev.agentdeck.companion.data.ThemeChoice
import dev.agentdeck.companion.notify.DeckNotifications
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.Banner
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.MachineMenu
import dev.agentdeck.companion.ui.NewChatScreen
import dev.agentdeck.companion.ui.PairScreen
import dev.agentdeck.companion.ui.ScheduledScreen
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.Times
import dev.agentdeck.companion.ui.UpdateBanner
import dev.agentdeck.companion.ui.formatCost
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The seam a debug build paints fixtures through, and the *only* thing about them that lives
 * in `main`.
 *
 * `provider` is null in a release build — nothing assigns it: the fixture data, and the
 * `ContentProvider` that installs this, are both in `src/debug`. It is here rather than in the
 * debug source set because [MainActivity] is a `main` class and cannot reference one.
 *
 * The point of the seam is that a screenshot travels the production route: the state is
 * synthetic, but every Composable, theme, inset and layout below `AgentDeckApp` is the shipped
 * one. A fixture that re-creates the screens would photograph itself.
 */
object DeckFixtureHook {
    var provider: ((String) -> DeckState?)? = null

    /** The `--es` extra `deck-screenshot.sh` passes; unknown or absent names paint normally. */
    const val EXTRA = "deck_fixture"
}

class MainActivity : ComponentActivity() {

    /** Links arrive before the composition exists and again while it is running. */
    private val links = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = intent?.getStringExtra(DeckFixtureHook.EXTRA)
            ?.let { name -> DeckFixtureHook.provider?.invoke(name) }
        links.value = intent?.dataString
        DeckNotifications.ensureChannels(this)
        // Android 15 forces edge-to-edge on a targetSdk-35 app, and an edge-to-edge window is
        // never resized by the manifest's `adjustResize` — so the keyboard opened *over* the
        // composer and the field the user was typing into was behind it. Opting in explicitly
        // makes every API level behave the one way, and the insets are then ours to spend
        // (`Modifier.imePadding()` below).
        enableEdgeToEdge()
        setContent { DeckRoot(fixture, links) }
    }

    /** The activity is `singleTask`, so a second tap on a notification lands here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        links.value = intent.dataString
    }
}

/**
 * Theme first, then the app: the theme is a *setting*, so it cannot be chosen above the view
 * model that holds it.
 */
@Composable
private fun DeckRoot(fixture: DeckState?, links: MutableStateFlow<String?>) {
    val model: DeckViewModel = viewModel()
    val live by model.state.collectAsStateWithLifecycle()
    val state = fixture ?: live
    val dark = when (state.settings.theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    // A fixture paints the brand scheme, never Material You: a wallpaper-derived palette
    // differs on every device, so a screenshot taken under one would be evidence about that
    // emulator's wallpaper rather than about this change.
    AgentDeckTheme(dark = dark, dynamic = fixture == null && state.settings.dynamicColor) {
        // A fixture pins the reader's clock as well as the palette. The sentences that ask
        // "is this today?" — the link banner's stamp, Settings' last snapshot, a schedule's
        // due time — read the phone otherwise, and a screenshot of one is then evidence about
        // the day it was taken: the four `fleet-*` goldens failed by the calendar for three
        // weeks that way. The fixture's own stamp is the instant it was written against.
        val pinned = fixture?.snapshot?.generatedAtMs?.takeIf { it > 0 }
        val readerClock: () -> Long =
            if (pinned == null) System::currentTimeMillis else ({ pinned })
        CompositionLocalProvider(LocalNow provides readerClock) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AgentDeckApp(model = model, state = state, links = links)
            }
        }
    }
}

/** Widths at which the phone layout stops being the right one. Material's own breakpoints. */
private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDeckApp(
    model: DeckViewModel,
    state: DeckState,
    links: MutableStateFlow<String?>? = null,
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val wide = widthDp >= MEDIUM_WIDTH_DP
    // Above this the fleet and the conversation fit side by side, which is the whole reason a
    // tablet is not a large phone: opening a row stops replacing the list you triage from.
    val twoPane = widthDp >= EXPANDED_WIDTH_DP && state.machine != null
    val snackbars = remember { SnackbarHostState() }

    links?.let { flow ->
        val pending by flow.collectAsStateWithLifecycle()
        LaunchedEffect(pending) {
            val parsed = Navigation.parse(pending) ?: return@LaunchedEffect
            flow.value = null
            model.open(parsed)
        }
    }

    NotificationPermission(state, model)

    state.snack?.let { snack ->
        LaunchedEffect(snack.id) {
            val result = snackbars.showSnackbar(
                message = snack.message,
                actionLabel = snack.undoLabel,
                withDismissAction = false,
            )
            if (result == SnackbarResult.ActionPerformed) model.undoSnack() else model.snackShown(snack.id)
        }
    }

    // Back is the system's gesture once there is nothing of ours left on the stack, so the
    // handler is enabled only while [DeckViewModel.back] has somewhere to go — which is what
    // makes predictive back animate out of the app instead of into a dead handler.
    BackHandler(enabled = state.backStack.isNotEmpty()) { model.back() }

    Scaffold(
        topBar = { if (state.screen !is Screen.Pair) DeckTopBar(state, model, twoPane) },
        bottomBar = {
            if (!wide && state.machine != null && Navigation.showsBar(state.screen)) {
                DeckNavigationBar(state, model)
            }
        },
        floatingActionButton = {
            // Starting a chat is the fleet's one creative act, and it was a 24 dp `+` in the
            // top-right corner — the hardest place on a phone to reach one-handed, competing
            // with three other icons. A FAB is the platform's own answer and the only control
            // for the action; the app-bar `+` is gone rather than duplicated.
            // The *list*, not the tab: a conversation and the new-chat composer both live
            // under Fleet, and a FAB there lands on the send button.
            if (state.screen is Screen.Fleet) {
                FloatingActionButton(onClick = model::openNewChat) {
                    Icon(Icons.Filled.Add, contentDescription = "New chat")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        // The keyboard's own inset. Without it the composer sits under the keyboard on any
        // edge-to-edge window, which every targetSdk-35 app is on Android 15.
        modifier = Modifier.imePadding(),
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            // A rail, not a bottom bar, once there is width for it: a bar 900 px wide puts
            // three icons in the middle of an empty strip and steals height the list wants.
            if (wide && state.machine != null && Navigation.showsBar(state.screen)) {
                DeckNavigationRail(state, model)
            }
            Column(Modifier.fillMaxSize()) {
                if (state.screen !is Screen.Pair) LinkBanner(state, model::unpair) { model.refreshFleet() }
                // Below the connection's row on purpose: a phone that cannot reach its machine
                // has a more urgent sentence to read than one about a newer build of this app.
                if (state.screen !is Screen.Pair) {
                    UpdateBanner(
                        update = state.update,
                        notices = state.settings.updateNotices,
                        onUpdate = model::downloadUpdate,
                        onInstall = model::installUpdate,
                        onDismiss = model::dismissUpdate,
                    )
                }

                if (twoPane && state.destination == Destination.FLEET) {
                    TwoPane(state, model)
                } else {
                    ScreenHost(state, model)
                }
            }
        }
    }
}

/**
 * The fleet beside whatever it opened. The list keeps a fixed column so a conversation
 * arriving does not reflow the rows the user is reading.
 */
@Composable
private fun TwoPane(state: DeckState, model: DeckViewModel) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(380.dp).fillMaxHeight()) {
            Fleet(state, model)
        }
        Surface(
            Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(topStart = 18.dp),
        ) {
            when (state.screen) {
                is Screen.Conversation, Screen.NewChat -> ScreenHost(state, model)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Pick a conversation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The destination itself, with motion between siblings.
 *
 * Screens used to swap with no transition at all, which on a phone reads as a redraw rather
 * than as travel. The direction is depth-derived: opening something slides in from the right,
 * going back slides out to it.
 */
@Composable
private fun ScreenHost(state: DeckState, model: DeckViewModel) {
    val depth = state.backStack.size
    AnimatedContent(
        targetState = state.screen,
        transitionSpec = {
            val forward = depth > 0
            val slide = if (forward) 1 else -1
            (
                slideInHorizontally(tween(220)) { width -> slide * width / 6 } + fadeIn(tween(220))
                ) togetherWith (
                slideOutHorizontally(tween(180)) { width -> -slide * width / 8 } + fadeOut(tween(180))
                )
        },
        label = "screen",
        modifier = Modifier.fillMaxSize(),
    ) { screen ->
        when (screen) {
            Screen.Pair -> PairScreen(
                state = state,
                onPair = model::pair,
                onScanned = model::pairFromQr,
                onDismissError = model::dismissPairError,
                onCancel = if (state.machines.isEmpty()) null else { { model.go(Destination.SETTINGS) } },
            )
            Screen.Fleet -> Fleet(state, model)
            is Screen.Conversation -> ConversationScreen(
                target = screen,
                page = state.transcript,
                loading = state.transcriptLoading,
                cached = state.transcriptCached,
                draft = state.drafts[screen.key].orEmpty(),
                notice = state.notice,
                onDraft = { model.setDraft(screen.key, it) },
                onSend = { text, stopFirst -> model.send(screen, text, stopFirst) },
                onStop = { model.stop(screen.key) },
                onDismissNotice = model::dismissNotice,
                canAnswer = MobileProtocol.Capability.ANSWER in state.hello?.capabilities.orEmpty(),
                onAnswer = { questionKey, label -> model.answer(screen, questionKey, label) },
            )
            Screen.Scheduled -> ScheduledScreen(
                rows = state.scheduled,
                loading = state.scheduledLoading,
                canCreate = MobileProtocol.Capability.SCHEDULE_CREATE in
                    state.hello?.capabilities.orEmpty(),
                projects = state.snapshot?.openProjects.orEmpty(),
                hello = state.hello,
                draft = state.drafts[DeckViewModel.SCHEDULE_DRAFT_KEY].orEmpty(),
                onDraft = { model.setDraft(DeckViewModel.SCHEDULE_DRAFT_KEY, it) },
                onRefresh = model::refreshScheduled,
                onCreate = model::createSchedule,
                onCommand = model::scheduledCommand,
            )
            Screen.Settings -> SettingsScreen(
                state = state,
                onSettings = model::updateSettings,
                onSwitchMachine = model::switchMachine,
                onAddMachine = model::addMachine,
                onUnpair = model::unpairFromMachine,
                onRefreshHello = model::refreshHello,
                onRefreshPush = model::refreshPush,
                onChoosePush = model::choosePushDistributor,
                onCheckUpdate = { model.checkForUpdates(manual = true) },
                onDownloadUpdate = model::downloadUpdate,
                onInstallUpdate = model::installUpdate,
                onReleasePage = model::openReleasePage,
            )
            Screen.NewChat -> {
                val target = state.newChatTarget
                NewChatScreen(
                    // A machine with nothing open has no target; the screen explains that
                    // state, so it is handed the same empty list it renders from.
                    target = target ?: dev.agentdeck.companion.data.NewChatTarget(""),
                    openProjects = if (target == null) emptyList()
                    else state.snapshot?.openProjects.orEmpty(),
                    vendors = NewChat.vendorOptions(state.snapshot?.rows.orEmpty()),
                    hello = state.hello,
                    draft = state.drafts[NEW_CHAT_DRAFT_KEY].orEmpty(),
                    sending = state.newChatSending,
                    notice = state.notice,
                    onTarget = model::setNewChatTarget,
                    onDraft = { model.setDraft(NEW_CHAT_DRAFT_KEY, it) },
                    onSend = model::startNewChat,
                    onDismissNotice = model::dismissNotice,
                )
            }
        }
    }
}

@Composable
private fun Fleet(state: DeckState, model: DeckViewModel) = FleetScreen(
    snapshot = state.snapshot,
    filter = state.filter,
    sort = state.sort,
    refreshing = state.refreshing,
    snoozed = state.snoozed,
    openKey = (state.screen as? Screen.Conversation)?.key,
    onFilter = model::setFilter,
    onSort = model::setSort,
    onRefresh = { model.refreshFleet() },
    onOpen = model::openConversation,
    onSnooze = model::snooze,
    onStop = { row -> model.stop(row.key, announce = "Stopped \"${row.title}\"") },
)

/** The three destinations, and the badge on the one that shows what it counts. */
@Composable
private fun DeckNavigationBar(state: DeckState, model: DeckViewModel) {
    NavigationBar {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = state.destination == destination,
                onClick = { onDestination(destination, state, model) },
                icon = { DestinationIcon(destination, state) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun DeckNavigationRail(state: DeckState, model: DeckViewModel) {
    NavigationRail {
        Column(
            Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Destination.entries.forEach { destination ->
                NavigationRailItem(
                    selected = state.destination == destination,
                    onClick = { onDestination(destination, state, model) },
                    icon = { DestinationIcon(destination, state) },
                    label = { Text(destination.label) },
                )
            }
        }
    }
}

/**
 * The badge sits on **Fleet**, and its tap scopes the list to what it counted.
 *
 * It used to wrap the Refresh icon: the count meant "3 agents are blocked on you" and the tap
 * meant "re-fetch", so the one control advertising the app's whole purpose answered by doing
 * something else. A badge belongs on the destination that holds the rows, which is the
 * Gmail/Slack/Linear reflex every user already has.
 */
@Composable
private fun DestinationIcon(destination: Destination, state: DeckState) {
    val icon: ImageVector = when (destination) {
        Destination.FLEET -> Icons.Filled.List
        Destination.SCHEDULED -> Icons.Filled.DateRange
        Destination.SETTINGS -> Icons.Filled.Settings
    }
    // Less what the user has already swiped aside: a badge of 3 over a list showing 1 is
    // the app arguing with itself.
    val badge = if (destination == Destination.FLEET) {
        Snooze.badge(state.snapshot?.badgeCount ?: 0, state.snoozed, state.snapshot?.rows.orEmpty())
    } else {
        0
    }
    BadgedBox(
        badge = {
            if (badge > 0) {
                Badge { Text(badge.toString()) }
            }
        },
    ) {
        Icon(
            icon,
            contentDescription = if (badge > 0) {
                "${destination.label}, $badge waiting on you"
            } else {
                destination.label
            },
        )
    }
}

private fun onDestination(destination: Destination, state: DeckState, model: DeckViewModel) {
    val waiting = Snooze.badge(state.snapshot?.badgeCount ?: 0, state.snoozed, state.snapshot?.rows.orEmpty()) > 0
    if (destination == Destination.FLEET && waiting) model.showWaiting() else model.go(destination)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckTopBar(state: DeckState, model: DeckViewModel, twoPane: Boolean) {
    TopAppBar(
        title = {
            Column {
                Text(
                    when (val screen = state.screen) {
                        is Screen.Conversation -> screen.title.ifBlank { "Conversation" }
                        Screen.Scheduled -> "Scheduled"
                        Screen.NewChat -> "New chat"
                        Screen.Settings -> "Settings"
                        else -> state.machine?.machineName?.ifBlank { "Agent Deck" } ?: "Agent Deck"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A conversation's cost, context and model used to be a second header *under*
                // the bar, beneath a second copy of the title the bar was already showing
                // (docs/img/2026-08-01-mobile-markdown.png). One title, and its numbers ride
                // with it.
                if (state.screen is Screen.Conversation) {
                    conversationSubtitle(state.transcript)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        navigationIcon = {
            // Two panes keep the list on screen, so there is nothing to go back *to*.
            if (state.backStack.isNotEmpty() && !twoPane) {
                IconButton(onClick = { model.back() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            // Only where more than one machine is paired: a switcher with one option in it
            // changes no decision (progressive disclosure), and Settings owns the rest.
            if (state.machines.size > 1 && Navigation.showsBar(state.screen)) {
                MachineMenu(
                    machines = state.machines,
                    active = state.machine?.id,
                    onSwitch = model::switchMachine,
                )
            }
        },
    )
}

/**
 * Asked once, after the first pairing, and explained before it is asked.
 *
 * A permission dialog on cold start arrives before the app has done anything, and is the one
 * every user denies without reading. This one appears when there is finally a machine to be
 * told about — and the sentence in front of it says what will be sent, because the system
 * dialog cannot.
 */
@Composable
private fun NotificationPermission(state: DeckState, model: DeckViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    var explaining by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { model.notificationPermissionAsked() }

    LaunchedEffect(state.askNotificationPermission) {
        if (state.askNotificationPermission) explaining = true
    }
    if (!explaining) return

    AlertDialog(
        onDismissRequest = {
            explaining = false
            model.notificationPermissionAsked()
        },
        title = { Text("Tell you when an agent needs you?") },
        text = {
            Text(
                "Agent Deck can notify you when a run is blocked on your answer, fails, or " +
                    "finishes. Each of those is its own channel, so you can tune them in " +
                    "Android's settings — and you can turn them off in Settings › Notifications.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                explaining = false
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = {
                explaining = false
                model.notificationPermissionAsked()
            }) { Text("Not now") }
        },
    )
}

/**
 * The open conversation's numbers, for the line under its title: cost, how full the context is,
 * which model. Null before the page arrives, so the bar does not reserve a line for nothing.
 */
private fun conversationSubtitle(page: MobileTranscriptPage?): String? {
    if (page == null) return null
    return buildString {
        append(formatCost(page.costUsd, page.costKnown))
        page.contextPct?.let { append(" · ").append(it).append("% context") }
        page.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }
}

/**
 * The connection's own row. A stale view is stamped with the age of the snapshot it is
 * showing and never with a word like "live"; there is no indefinite spinner, because a
 * phone that cannot reach the machine is not waiting for anything.
 */
@Composable
private fun LinkBanner(state: DeckState, onUnpair: () -> Unit, onRetry: () -> Unit) {
    val now = LocalNow.current()
    val stamp = state.snapshot?.generatedAtMs?.takeIf { it > 0 }
        ?.let { "as of ${Times.clock(it, now)}" }
    when (val link = state.link) {
        Link.Live -> Unit
        Link.Connecting -> Banner("Connecting…", MaterialTheme.colorScheme.surfaceVariant)
        // Named separately from Stale so the sentence blames the end that is actually at
        // fault: "the machine is not answering" about a phone in a tunnel sends the user to
        // check an IDE that is running perfectly.
        Link.Offline -> Banner(
            listOfNotNull("This phone is offline.", stamp).joinToString(" · "),
            MaterialTheme.colorScheme.surfaceVariant,
        )
        is Link.Stale -> Banner(
            listOfNotNull(link.reason, stamp).joinToString(" · "),
            MaterialTheme.colorScheme.surfaceVariant,
        ) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        is Link.Repair -> Banner(link.reason, MaterialTheme.colorScheme.errorContainer) {
            // A pin mismatch or a revoked token has no override: the only way forward is
            // to pair again.
            TextButton(onClick = onUnpair) { Text("Pair again") }
        }
    }
}

