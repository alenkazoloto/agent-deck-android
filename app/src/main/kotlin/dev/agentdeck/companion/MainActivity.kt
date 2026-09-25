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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.claudeagents.core.mobile.MobileAcpSession
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewView
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.ComposerStashes
import dev.agentdeck.companion.data.ConversationOutline
import dev.agentdeck.companion.data.conversationWaiting
import dev.agentdeck.companion.data.LimitContinuation
import dev.agentdeck.companion.ui.ProvideCodeWrap
import dev.agentdeck.companion.ui.ProvideOpenToolCalls
import dev.agentdeck.companion.ui.ScheduleSourcesOffer
import dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.ShareIntent
import dev.agentdeck.companion.data.SharedInput
import dev.agentdeck.companion.data.ReviewQueue
import dev.agentdeck.companion.data.Snooze
import dev.agentdeck.companion.data.ThemeChoice
import dev.agentdeck.companion.notify.DeckNotifications
import dev.agentdeck.companion.ui.acpAgentVoice
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.Banner
import dev.agentdeck.companion.ui.ComposerStashActions
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalMarkdownImages
import dev.agentdeck.companion.ui.MarkdownImageLoader
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.height
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.LimitContinuationOffer
import dev.agentdeck.companion.ui.ScheduleIntoChatOffer
import dev.agentdeck.companion.ui.MachinePicker
import dev.agentdeck.companion.ui.NewChatScreen
import dev.agentdeck.companion.ui.WorktreeActions
import dev.agentdeck.companion.ui.WorktreeSheetView
import dev.agentdeck.companion.ui.ChangeRequestReviewView
import dev.agentdeck.companion.ui.ReviewActions
import dev.agentdeck.companion.ui.ReviewEditActions
import dev.agentdeck.companion.ui.RepositoryActions
import dev.agentdeck.companion.ui.RepositoryButtons
import dev.agentdeck.companion.ui.RepositorySheetView
import dev.agentdeck.companion.ui.PairScreen
import dev.agentdeck.companion.ui.ReviewScreen
import dev.agentdeck.companion.ui.ScheduledScreen
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.UsageScreen
import dev.agentdeck.companion.ui.ShareBanner
import dev.agentdeck.companion.ui.Times
import dev.agentdeck.companion.ui.UpdateBanner
import dev.agentdeck.companion.ui.formatCost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** The commit sheet a fixture paints; it lives in `ReviewCommitFlow`, outside [DeckState]. */
    var commitSheet: ((String) -> dev.agentdeck.companion.data.CommitSheet?)? = null
    var paintedCommitSheet: dev.agentdeck.companion.data.CommitSheet? = null

    /** New chat's worktree pick a fixture paints; it lives in `WorktreeStartFlow`, outside [DeckState]. */
    var worktree: ((String) -> dev.agentdeck.companion.data.WorktreeChoice?)? = null
    var paintedWorktree: dev.agentdeck.companion.data.WorktreeChoice? = null

    /** The Manage worktrees sheet a fixture paints; it lives in `WorktreeManageFlow`. */
    var worktreeSheet: ((String) -> dev.agentdeck.companion.data.WorktreeSheet?)? = null
    var paintedWorktreeSheet: dev.agentdeck.companion.data.WorktreeSheet? = null

    /** The change-request review sheet a fixture paints; it lives in `ChangeRequestReviewFlow`. */
    var reviewSheet: ((String) -> dev.agentdeck.companion.data.ReviewSheet?)? = null
    var paintedReviewSheet: dev.agentdeck.companion.data.ReviewSheet? = null

    /** The Clone/Publish repository sheet a fixture paints; its `-button` fixture marks the project publishable. */
    var repositorySheet: ((String) -> dev.agentdeck.companion.data.RepositorySheet?)? = null
    var paintedRepositorySheet: dev.agentdeck.companion.data.RepositorySheet? = null
    var paintedPublishable: Boolean = false

    /** The Commit staged sheet a fixture paints; it lives in `CommitStagedFlow`. */
    var commitStaged: ((String) -> dev.agentdeck.companion.data.CommitStagedSheet?)? = null
    var paintedCommitStaged: dev.agentdeck.companion.data.CommitStagedSheet? = null

    /** The revert sheet a fixture paints, for the same reason. */
    var revertSheet: ((String) -> dev.agentdeck.companion.data.RevertSheet?)? = null
    var paintedRevertSheet: dev.agentdeck.companion.data.RevertSheet? = null

    /** The rewind picker a fixture paints; it lives in `SessionRewindFlow`, outside [DeckState]. */
    var rewind: ((String) -> dev.agentdeck.companion.data.RewindPicker?)? = null
    var paintedRewind: dev.agentdeck.companion.data.RewindPicker? = null

    /** Codex's review sheet a fixture paints; it lives in `AiReviewFlow`, outside [DeckState]. */
    var aiReview: ((String) -> dev.agentdeck.companion.data.AiReviewSheet?)? = null
    var paintedAiReview: dev.agentdeck.companion.data.AiReviewSheet? = null

    /** The context grid a fixture paints; it lives in `ContextFlow`, outside [DeckState]. */
    var context: ((String) -> dev.agentdeck.companion.data.ContextSheet?)? = null
    var paintedContext: dev.agentdeck.companion.data.ContextSheet? = null

    /** The working-directories sheet a fixture paints; it lives in `SessionDirsFlow`, outside [DeckState]. */
    var sessionDirs: ((String) -> dev.agentdeck.companion.data.SessionDirsSheet?)? = null
    var paintedSessionDirs: dev.agentdeck.companion.data.SessionDirsSheet? = null
    /** The `/btw` sheet a fixture paints; it lives in `SideQuestionFlow`, outside [DeckState]. */
    var sideQuestion: ((String) -> dev.agentdeck.companion.data.SideQuestionSheet?)? = null
    var paintedSideQuestion: dev.agentdeck.companion.data.SideQuestionSheet? = null

    /** The spend-limits sheet a fixture paints; it lives in `SessionSpendFlow`, outside [DeckState]. */
    var sessionSpend: ((String) -> dev.agentdeck.companion.data.SessionSpendSheet?)? = null
    var paintedSessionSpend: dev.agentdeck.companion.data.SessionSpendSheet? = null

    /** Codex's `/diff` sheet a fixture paints; it lives in `WorkingDiffFlow`, outside [DeckState]. */
    var workingDiff: ((String) -> dev.agentdeck.companion.data.WorkingDiffSheet?)? = null
    var paintedWorkingDiff: dev.agentdeck.companion.data.WorkingDiffSheet? = null

    /** The review notes a fixture paints — the sheet, the editor or the diff's banners. */
    var notes: ((String) -> dev.agentdeck.companion.data.NotesState?)? = null
    var paintedNotes: dev.agentdeck.companion.data.NotesState? = null

    /** The Changes tab's request scope a fixture opens on; it is the tab's own saved state. */
    var scope: ((String) -> String?)? = null
    var paintedScope: String? = null
}

/** A link and, from a notification, the pairing whose fleet posted it. */
data class IncomingLink(val raw: String, val machineId: String? = null) {
    companion object {
        fun of(intent: Intent?): IncomingLink? = intent?.dataString
            ?.let { IncomingLink(it, intent.getStringExtra(MainActivity.EXTRA_MACHINE)) }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        /** The pairing a notification's conversation belongs to ([dev.agentdeck.companion.notify.Alert.machineId]). */
        const val EXTRA_MACHINE = "dev.agentdeck.companion.MACHINE"
    }

    /** Links arrive before the composition exists and again while it is running. */
    private val links = MutableStateFlow<IncomingLink?>(null)

    /** The same, for an `ACTION_SEND` — read off the intent rather than off its URI. */
    private val shared = MutableStateFlow<SharedInput?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = intent?.getStringExtra(DeckFixtureHook.EXTRA)
            ?.let { name -> DeckFixtureHook.provider?.invoke(name).also { DeckFixtureHook.paintedCommitSheet = DeckFixtureHook.commitSheet?.invoke(name); DeckFixtureHook.paintedRevertSheet = DeckFixtureHook.revertSheet?.invoke(name); DeckFixtureHook.paintedRewind = DeckFixtureHook.rewind?.invoke(name); DeckFixtureHook.paintedNotes = DeckFixtureHook.notes?.invoke(name); DeckFixtureHook.paintedAiReview = DeckFixtureHook.aiReview?.invoke(name); DeckFixtureHook.paintedContext = DeckFixtureHook.context?.invoke(name); DeckFixtureHook.paintedSessionDirs = DeckFixtureHook.sessionDirs?.invoke(name); DeckFixtureHook.paintedSideQuestion = DeckFixtureHook.sideQuestion?.invoke(name); DeckFixtureHook.paintedSessionSpend = DeckFixtureHook.sessionSpend?.invoke(name); DeckFixtureHook.paintedWorkingDiff = DeckFixtureHook.workingDiff?.invoke(name); DeckFixtureHook.paintedWorktree = DeckFixtureHook.worktree?.invoke(name); DeckFixtureHook.paintedWorktreeSheet = DeckFixtureHook.worktreeSheet?.invoke(name); DeckFixtureHook.paintedReviewSheet = DeckFixtureHook.reviewSheet?.invoke(name); DeckFixtureHook.paintedRepositorySheet = DeckFixtureHook.repositorySheet?.invoke(name); DeckFixtureHook.paintedPublishable = DeckFixtureHook.repositorySheet != null && name.startsWith("new-chat-repository-"); DeckFixtureHook.paintedCommitStaged = DeckFixtureHook.commitStaged?.invoke(name); DeckFixtureHook.paintedScope = DeckFixtureHook.scope?.invoke(name) } }
        // Once per intent: a recreation (rotation) keeps the used notification intent, and running
        // its link again would switch back to the machine the reader has since left.
        if (savedInstanceState == null) links.value = IncomingLink.of(intent)
        ingestShare(intent)
        DeckNotifications.ensureChannels(this)
        // Android 15 forces edge-to-edge on a targetSdk-35 app, and an edge-to-edge window is
        // never resized by the manifest's `adjustResize` — so the keyboard opened *over* the
        // composer and the field the user was typing into was behind it. Opting in explicitly
        // makes every API level behave the one way, and the insets are then ours to spend
        // (`Modifier.imePadding()` below).
        enableEdgeToEdge()
        setContent { DeckRoot(fixture, links, shared) }
    }

    /** The activity is `singleTask`, so a second tap on a notification lands here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        links.value = IncomingLink.of(intent)
        ingestShare(intent)
    }

    /**
     * Off the main thread: a shared file is read here, and the provider behind a `content://`
     * URI belongs to whichever app the user shared from.
     */
    private fun ingestShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        lifecycleScope.launch {
            shared.value = withContext(Dispatchers.IO) { ShareIntent.read(this@MainActivity, intent) }
        }
    }
}

/**
 * Theme first, then the app: the theme is a *setting*, so it cannot be chosen above the view
 * model that holds it.
 */
@Composable
private fun DeckRoot(
    fixture: DeckState?,
    links: MutableStateFlow<IncomingLink?>,
    shared: MutableStateFlow<SharedInput?>,
) {
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
            // Settings › Reading, one answer for both places this app shows code: a diff line
            // takes it as a parameter, a markdown fence cannot be handed anything. Through the
            // provider `CodeText` declares, so a golden can enter the state the app enters.
            ProvideCodeWrap(state.settings.diffSoftWrap) {
                ProvideOpenToolCalls(state.settings.openToolCalls) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AgentDeckApp(model = model, state = state, links = links, shared = shared)
                    }
                }
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
    links: MutableStateFlow<IncomingLink?>? = null,
    shared: MutableStateFlow<SharedInput?>? = null,
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val wide = widthDp >= MEDIUM_WIDTH_DP
    // Above this the fleet and the conversation fit side by side, which is the whole reason a
    // tablet is not a large phone: opening a row stops replacing the list you triage from.
    val twoPane = widthDp >= EXPANDED_WIDTH_DP && state.machine != null
    // The field wins over the bar when the keyboard leaves no room for both; system Back closes
    // the keyboard first, and the bar with its visible Back comes back with the room.
    val keyboardCrowds = dev.agentdeck.companion.ui.KeyboardRoom.crowded()
    val snackbars = remember { SnackbarHostState() }
    val snackbarLift = remember { androidx.compose.runtime.mutableStateMapOf<Any, androidx.compose.ui.unit.Dp>() }

    links?.let { flow ->
        val pending by flow.collectAsStateWithLifecycle()
        LaunchedEffect(pending) {
            val incoming = pending ?: return@LaunchedEffect
            val parsed = Navigation.parse(incoming.raw) ?: return@LaunchedEffect
            flow.value = null
            model.open(parsed, incoming.machineId)
        }
    }

    shared?.let { flow ->
        val pending by flow.collectAsStateWithLifecycle()
        LaunchedEffect(pending) {
            val input = pending ?: return@LaunchedEffect
            flow.value = null
            model.share(input)
        }
    }

    NotificationPermission(state, model)

    // Above every screen: the picker opens from a Chats row and from a conversation's `/rewind`.
    val rewindPicker by model.rewind.picker.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedRewind ?: rewindPicker)?.let { picker ->
        dev.agentdeck.companion.ui.RewindDialog(
            picker = picker,
            onDismiss = model.rewind::dismiss,
            onChoose = model.rewind::choose,
            onScope = model.rewind::run,
            onBack = model.rewind::back,
        )
    }
    // A Chats row's "Commit staged changes…"; above every screen, like the rewind picker.
    val commitStagedSheet by model.commitStaged.sheet.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedCommitStaged ?: commitStagedSheet)?.let { sheet ->
        dev.agentdeck.companion.ui.CommitStagedSheetView(
            sheet = sheet,
            onSubject = model.commitStaged::editSubject,
            onBody = model.commitStaged::editBody,
            onRename = model.commitStaged::setRename,
            onRenameTo = model.commitStaged::editRenameTo,
            onWrite = model.commitStaged::write,
            onCommit = model.commitStaged::confirm,
            onDismiss = { DeckFixtureHook.paintedCommitStaged = null; model.commitStaged.dismiss() },
        )
    }
    // A Chats row's "Context window…" and a conversation's `/context`; above every screen, like the rewind picker.
    val contextSheet by model.context.sheet.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedContext ?: contextSheet)?.let { sheet -> dev.agentdeck.companion.ui.ContextDialog(sheet, onDismiss = { DeckFixtureHook.paintedContext = null; model.context.dismiss() }) }
    // A Chats row's "Working directories…"; above every screen, like the context grid.
    val dirsSheet by model.sessionDirs.sheet.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedSessionDirs ?: dirsSheet)?.let { sheet ->
        dev.agentdeck.companion.ui.SessionDirsDialog(
            sheet,
            onDraft = model.sessionDirs::edit,
            onAdd = model.sessionDirs::add,
            onRemove = model.sessionDirs::remove,
            onDismiss = { DeckFixtureHook.paintedSessionDirs = null; model.sessionDirs.dismiss() },
        )
    }
    // A Chats row's "Spend limits…"; above every screen, like the working-directories sheet.
    val spendSheet by model.sessionSpend.sheet.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedSessionSpend ?: spendSheet)?.let { sheet ->
        dev.agentdeck.companion.ui.SessionSpendDialog(
            sheet,
            onForm = model.sessionSpend::edit,
            onSave = model.sessionSpend::save,
            onDismiss = { DeckFixtureHook.paintedSessionSpend = null; model.sessionSpend.dismiss() },
        )
    }
    // A conversation's `/btw`; above every screen, like the context grid.
    val sideQuestionSheet by model.sideQuestion.sheet.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedSideQuestion ?: sideQuestionSheet)?.let { sheet ->
        dev.agentdeck.companion.ui.SideQuestionDialog(
            sheet,
            onDraft = model.sideQuestion::edit,
            onAsk = model.sideQuestion::ask,
            onDismiss = { DeckFixtureHook.paintedSideQuestion = null; model.sideQuestion.dismiss() },
        )
    }
    // A conversation's `/review` in a Codex chat; above every screen, like the rewind picker.
    val aiReviewSheet by model.aiReview.sheet.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedAiReview ?: aiReviewSheet)?.let { sheet ->
        dev.agentdeck.companion.ui.AiReviewDialog(
            sheet = sheet,
            onDismiss = model.aiReview::dismiss,
            onEdit = model.aiReview::edit,
            onStart = model.aiReview::start,
            onStop = model.aiReview::stop,
            onFix = model::askToFix,
            onNeverReport = model.aiReview::neverReport,
            onOpenRules = model.aiReview::openRules,
            onEditRules = model.aiReview::editRules,
            onSaveRules = model.aiReview::saveRules,
            onCloseRules = model.aiReview::closeRules,
            onOpenLocation = model.aiReview::openLocation
                .takeIf { MobileProtocol.Capability.AI_REVIEW_LOCATION in state.hello?.capabilities.orEmpty() },
            onCloseLocation = model.aiReview::closeLocation,
        )
    }
    // A Codex conversation's `/diff`; above every screen, like the review sheet.
    val workingDiffSheet by model.workingDiff.sheet.collectAsStateWithLifecycle()
    (DeckFixtureHook.paintedWorkingDiff ?: workingDiffSheet)?.let { sheet ->
        dev.agentdeck.companion.ui.WorkingDiffDialog(
            sheet = sheet,
            onDismiss = { DeckFixtureHook.paintedWorkingDiff = null; model.workingDiff.dismiss() },
            onOpenFile = model.workingDiff::openFile,
            onCloseFile = model.workingDiff::closeFile,
            onRefresh = model.workingDiff::refresh,
        )
    }

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
    BackHandler(enabled = Navigation.backTarget(state.screen, state.backStack) != null) { model.back() }

    CompositionLocalProvider(dev.agentdeck.companion.ui.LocalSnackbarLift provides snackbarLift) { Scaffold(
        topBar = { if (state.screen !is Screen.Pair && !keyboardCrowds) DeckTopBar(state, model) },
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
        snackbarHost = { SnackbarHost(snackbars, Modifier.padding(bottom = snackbarLift.height())) },
        // The keyboard's own inset. Without it the composer sits under the keyboard on any
        // edge-to-edge window, which every targetSdk-35 app is on Android 15.
        modifier = Modifier.imePadding(),
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            // A rail, not a bottom bar, once there is width for it: a bar 900 px wide puts
            // three icons in the middle of an empty strip and steals height the list wants.
            if (wide && state.machine != null && state.screen != Screen.Pair) {
                DeckNavigationRail(state, model)
            }
            Column(Modifier.fillMaxSize()) {
                if (state.screen !is Screen.Pair) LinkBanner(state, model::pairAgain) { model.refreshFleet() }
                // Below the connection's row on purpose: a phone that cannot reach its machine
                // has a more urgent sentence to read than one about a newer build of this app.
                if (state.screen !is Screen.Pair) {
                    // Above the update row and below the connection's: a share the user is
                    // mid-gesture on outranks news about a newer build of this app.
                    ShareBanner(state.sharing, model::dismissShare)
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
    } }
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
            Screen.Review -> ReviewScreen(
                snapshot = state.snapshot,
                // No hello yet (offline, cached list) is not "too old": the projection needs none.
                canReview = state.hello?.let { MobileProtocol.Capability.REVIEW in it.capabilities } ?: true,
                machineName = state.machine?.machineName,
                onOpen = model::openForReview,
                onMarkReviewed = { row -> model.markReviewed(row.key, emptyList(), reviewed = true) },
            )
            is Screen.Conversation -> {
            // Absent capability, absent surface: an older plugin keeps the one-line note.
            val canDrawImages = MobileProtocol.Capability.IMAGES in state.hello?.capabilities.orEmpty()
            val imageLoader: MarkdownImageLoader? = remember(screen.key, canDrawImages) {
                if (canDrawImages) ({ src -> model.conversationImage(screen.key, src) }) else null
            }
            val notesState by model.reviewNotes.state.collectAsStateWithLifecycle()
            val feedbackChips = MobileProtocol.Capability.REVIEW_FEEDBACK_CHIPS in state.hello?.capabilities.orEmpty() && !MobileAcpSession.isKey(screen.key)
            val chatRunning = state.transcript?.takeIf { it.key == screen.key }?.running == true
            // On opening, and again when a run ends: that is when a send's feedback turns delivered or failed.
            var ranBefore by remember(screen.key) { mutableStateOf(false) }
            LaunchedEffect(screen.key, feedbackChips) { if (feedbackChips) model.reviewNotes.load(screen.key) }
            LaunchedEffect(chatRunning) {
                if (feedbackChips && ranBefore && !chatRunning) model.reviewNotes.load(screen.key)
                ranBefore = chatRunning
            }
            CompositionLocalProvider(LocalMarkdownImages provides imageLoader) { ConversationScreen(
                target = screen,
                agentName = acpAgentVoice(screen.key, state.snapshot?.rows?.firstOrNull { it.key == screen.key }?.accountLabel),
                readingScope = state.machine?.id.orEmpty(),
                readingAnchor = state.readingPositions.conversations[screen.key],
                onReadingAnchor = { model.rememberReading(state.machine?.id, screen.key, it) },
                // AnimatedContent keeps the outgoing conversation composed during the transition.
                page = state.transcript?.takeIf { it.key == screen.key },
                waiting = state.snapshot?.rows?.firstOrNull { it.key == screen.key }?.let(::conversationWaiting),
                limitContinuation = run {
                    val row = state.snapshot?.rows?.firstOrNull { it.key == screen.key }
                    val reset = LimitContinuation.forChat(state.hello, row, running = state.transcript?.takeIf { it.key == screen.key }?.running == true, LocalNow.current())
                    val draftKey = LimitContinuation.draftKey(screen.key)
                    reset?.let {
                        LimitContinuationOffer(
                            reset = it,
                            accountLabel = row?.accountLabel,
                            prompt = state.drafts[draftKey] ?: LimitContinuation.PROMPT,
                            onPrompt = { text -> model.setDraft(draftKey, text) },
                            onSchedule = { dueAtMs -> model.scheduleContinuation(screen, dueAtMs) },
                        )
                    }
                },
                scheduleIntoChat = ScheduleIntoChatOffer(
                    accountId = state.snapshot?.rows?.firstOrNull { it.key == screen.key }?.accountId?.takeIf { it.isNotEmpty() },
                    canRepeat = MobileProtocol.Capability.SCHEDULE_REPEAT in state.hello?.capabilities.orEmpty(),
                    canAfterRun = MobileProtocol.Capability.SCHEDULE_AFTER_RUN in state.hello?.capabilities.orEmpty() &&
                        state.transcript?.takeIf { it.key == screen.key }?.running == true,
                    sources = scheduleSourcesOffer(state, model),
                    onSchedule = { dueAtMs, repeat, afterRun, dependencies -> model.scheduleIntoChat(screen, dueAtMs, repeat, afterRun, dependencies) },
                    habit = state.scheduleHabit,
                    onHabit = model::rememberScheduleHabit,
                    form = state.scheduleChatForms[screen.key],
                    onForm = { model.rememberScheduleChatForm(screen.key, it) },
                ).takeIf { MobileProtocol.Capability.SCHEDULE_CREATE in state.hello?.capabilities.orEmpty() && !MobileAcpSession.isKey(screen.key) },
                loading = state.transcriptLoading,
                cached = state.transcriptCached,
                draft = state.drafts[screen.key].orEmpty(),
                notice = state.notice,
                onDraft = { model.editDraft(screen.key, it) },
                onSend = { text, stopFirst -> model.send(screen, text, stopFirst) },
                onStop = { model.stop(screen.key) },
                stopping = screen.key in state.stopping,
                onDismissNotice = model::dismissNotice,
                queued = state.outgoing.forKey(screen.key),
                delivering = state.delivering,
                onRetryQueued = model::retryQueued,
                onEditQueued = model::editQueued,
                onClearGoal = { model.clearGoal(screen.key) },
                onTakeSuggestion = { model.editDraft(screen.key, it) },
                recap = state.recap?.takeIf { it.key == screen.key }?.recap,
                onDismissRecap = model::dismissRecap,
                onDiscardQueued = model::discardQueued,
                onRetryTurn = { prompt -> model.retryFailedTurn(screen, prompt) },
                canPage = MobileProtocol.Capability.TRANSCRIPT_PAGING in state.hello?.capabilities.orEmpty(),
                earlierLoading = state.earlierLoading,
                earlierError = state.earlierError,
                earlierExpired = state.earlierExpired,
                onEarlier = { model.loadEarlier(screen.key) },
                onRefreshHistory = { model.refreshHistory(screen.key) },
                hasPendingLatest = state.pendingTranscript?.key == screen.key,
                onAcceptLatest = { model.acceptLatest(screen.key) },
                findOpen = state.findConversationKey == screen.key,
                onCloseFind = { model.findInConversation(false) },
                outlineOpen = state.outlineConversationKey == screen.key,
                onOutline = model::outlineConversation,
                onRefresh = { model.refreshTranscript(screen.key) },
                refreshing = state.transcriptRefreshing,
                onStartFromPrompt = { prompt -> model.openNewChat(prompt) },
                onDeskNavigation = model::deskNavigation,
                onCommandNotice = model::announce,
                onRename = { title: String -> model.renameConversation(screen.key, title) }
                    .takeIf { MobileProtocol.Capability.SESSION_ACTIONS in state.hello?.capabilities.orEmpty() },
                renameTitle = state.snapshot?.rows?.firstOrNull { it.key == screen.key }?.title ?: screen.title,
                onSideQuestion = { question -> model.sideQuestion.open(screen.key, state.snapshot?.rows?.firstOrNull { it.key == screen.key }?.title ?: screen.title, question) },
                canAnswer = MobileProtocol.Capability.ANSWER in state.hello?.capabilities.orEmpty(),
                onAnswer = { askId, answers -> model.answer(screen, askId, answers) },
                onAnswerTyped = { askId: String, answers: Map<String, String>, typed: Set<String> -> model.answer(screen, askId, answers, typed) }
                    .takeIf { MobileProtocol.Capability.ANSWER_TYPED in state.hello?.capabilities.orEmpty() },
                answerFailures = state.answerFailures,
                canDecide = MobileProtocol.Capability.PERMISSION_DECISIONS in state.hello?.capabilities.orEmpty(),
                onDecide = { requestId, decision -> model.decide(screen, requestId, decision) },
                onDecidePlan = { requestId, decision, mode, feedback -> model.decide(screen, requestId, decision, mode, feedback) },
                decideFailures = state.decideFailures,
                hello = state.hello,
                selection = state.composerSelection(screen.key),
                onPick = { field, value -> model.setComposerPick(screen.key, field, value) },
                toolOutput = state.toolOutput,
                toolOutputCallId = state.toolOutputCallId,
                toolOutputLoading = state.toolOutputLoading,
                toolOutputError = state.toolOutputError,
                onLoadToolOutput = { callId -> model.loadToolOutput(screen.key, callId) },
                onCloseToolOutput = model::clearToolOutput,
                photos = state.pendingPhotos[screen.key].orEmpty(),
                attaching = state.attaching,
                onAttach = { bytes -> model.attachPhoto(screen.key, bytes) },
                onAttachFile = { name, bytes -> model.attachFile(screen.key, name, bytes) },
                onPastedText = { text -> model.attachPastedText(screen.key, text) },
                onRemovePhoto = { id -> model.removePhoto(screen.key, id) },
                onSearchFiles = { query -> model.searchFiles(screen.projectPath, query, screen.key, screen.vendor) },
                onSearchIssues = { query -> model.searchIssues(screen.projectPath, query) },
                onLoadCommands = { model.loadCommands(screen.key, screen.vendor, screen.projectPath) },
                onLoadPrompts = { model.loadPrompts(screen.key, screen.vendor, screen.projectPath) },
                stash = ComposerStashActions(
                    entries = ComposerStashes.entries(state.drafts, screen.key),
                    onStash = { model.stashDraft(screen.key) },
                    onRestore = { id -> model.restoreStashed(screen.key, id) },
                    onDiscard = { id -> model.discardStashed(screen.key, id) },
                ),
                // An ACP chat's edits are not in the review store the Changes tab reads.
                canReview = MobileProtocol.Capability.REVIEW in state.hello?.capabilities.orEmpty() && !MobileAcpSession.isKey(screen.key),
                changesOpen = state.changesOpenKey == screen.key,
                onChangesOpen = { open -> model.showChanges(screen.key, open) },
                review = state.review?.takeIf { it.key == screen.key },
                reviewLoading = state.reviewLoading,
                reviewError = state.reviewError,
                reviewMarking = state.reviewMarking,
                reviewPath = state.reviewPath,
                reviewDiff = state.reviewDiff,
                reviewDiffLoading = state.reviewDiffLoading,
                reviewDiffError = state.reviewDiffError,
                diffSoftWrap = state.settings.diffSoftWrap,
                diffLineNumbers = state.settings.diffLineNumbers,
                emojiCompletion = state.settings.emojiCompletion,
                onOpenChanges = {
                    model.loadReview(screen.key)
                    if (MobileProtocol.Capability.REVIEW_NOTES in state.hello?.capabilities.orEmpty()) model.reviewNotes.load(screen.key)
                },
                onOpenReviewFile = { path, request, base -> model.openReviewFile(screen.key, path, request, base) },
                diffBases = MobileProtocol.Capability.REVIEW_DIFF_BASE in state.hello?.capabilities.orEmpty(),
                onIgnoreWhitespace = model::setDiffIgnoreWhitespace
                    .takeIf { MobileProtocol.Capability.REVIEW_IGNORE_WHITESPACE in state.hello?.capabilities.orEmpty() },
                onReviewSort = { order: String -> model.setReviewSort(screen.key, order) }
                    .takeIf { MobileProtocol.Capability.REVIEW_SORT in state.hello?.capabilities.orEmpty() },
                onReviewView = { view: MobileReviewView -> model.setReviewView(screen.key, view) }
                    .takeIf { MobileProtocol.Capability.REVIEW_VIEW_OPTIONS in state.hello?.capabilities.orEmpty() },
                onCloseReviewFile = model::closeReviewFile,
                onMarkReviewed = { paths, reviewed -> model.markReviewed(screen.key, paths, reviewed) },
                onCommitChanges = { scoped: Set<String>? -> model.reviewCommit.open(screen.key, only = scoped) }
                    .takeIf { MobileProtocol.Capability.REVIEW_COMMIT in state.hello?.capabilities.orEmpty() },
                onRevertChanges = { scoped: Set<String>? -> model.reviewRevert.open(screen.key, keep = scoped) }
                    .takeIf { MobileProtocol.Capability.REVIEW_REVERT in state.hello?.capabilities.orEmpty() },
                reviewScope = DeckFixtureHook.paintedScope,
                feedback = if (!feedbackChips) null else dev.agentdeck.companion.ui.ReviewFeedbackUi(
                    chips = (DeckFixtureHook.paintedNotes ?: notesState)?.takeIf { it.key == screen.key }
                        ?.chips(state.drafts[screen.key].orEmpty()).orEmpty(),
                    onRetry = { chip -> model.reviewNotes.retry(chip.batchId) },
                    onRemove = { chip -> model.reviewNotes.detachFile(screen.key, chip) },
                ),
                reviewNotes = if (MobileProtocol.Capability.REVIEW_NOTES !in state.hello?.capabilities.orEmpty()) null else {
                    dev.agentdeck.companion.ui.ReviewNotesUi(
                        notes = (DeckFixtureHook.paintedNotes ?: notesState)?.takeIf { it.key == screen.key }?.open.orEmpty(),
                        onOpenList = { model.reviewNotes.openList(screen.key) },
                        onAdd = { path, side, line, following -> model.reviewNotes.startAdd(screen.key, path, side, line, following) },
                        onOpenNote = model.reviewNotes::startEdit,
                        moving = (DeckFixtureHook.paintedNotes ?: notesState)?.takeIf { it.key == screen.key }?.movingNote,
                        onCancelMove = model.reviewNotes::cancelMove,
                    )
                },
            ) }
            val commitSheet by model.reviewCommit.sheet.collectAsStateWithLifecycle()
            (DeckFixtureHook.paintedCommitSheet ?: commitSheet)?.takeIf { it.key == screen.key }?.let { sheet ->
                dev.agentdeck.companion.ui.CommitSheetView(
                    sheet = sheet,
                    onMessage = model.reviewCommit::editMessage,
                    onToggle = model.reviewCommit::toggle,
                    onCommit = model.reviewCommit::confirm,
                    onDismiss = model.reviewCommit::dismiss,
                )
            }
            (DeckFixtureHook.paintedNotes ?: notesState)?.takeIf { it.key == screen.key }?.let { notes ->
                if (notes.listing && notes.editor == null) dev.agentdeck.companion.ui.ReviewNotesSheetView(
                    state = notes,
                    onToggle = model.reviewNotes::toggle,
                    onHistory = model.reviewNotes::toggleHistory,
                    onEdit = model.reviewNotes::startEdit,
                    onResolve = model.reviewNotes::resolve,
                    onDelete = model.reviewNotes::delete,
                    onAttach = model.reviewNotes::attach,
                    onReattach = model.reviewNotes::startMove
                        .takeIf { MobileProtocol.Capability.REVIEW_NOTE_REATTACH in state.hello?.capabilities.orEmpty() },
                    onRetry = model.reviewNotes::retry,
                    onDismiss = model.reviewNotes::closeList,
                )
                notes.editor?.let { editor ->
                    dev.agentdeck.companion.ui.NoteEditorSheet(
                        editor = editor,
                        onBody = model.reviewNotes::editBody,
                        onExtend = model.reviewNotes::extend,
                        onSave = model.reviewNotes::save,
                        onDismiss = model.reviewNotes::cancelEditor,
                    )
                }
            }
            val revertSheet by model.reviewRevert.sheet.collectAsStateWithLifecycle()
            (DeckFixtureHook.paintedRevertSheet ?: revertSheet)?.takeIf { it.key == screen.key }?.let { sheet ->
                dev.agentdeck.companion.ui.RevertSheetView(
                    sheet = sheet,
                    onToggle = model.reviewRevert::toggle,
                    onRevert = model.reviewRevert::confirm,
                    onDismiss = model.reviewRevert::dismiss,
                    onChoose = model.reviewRevert::choose,
                )
            }
            }
            Screen.Scheduled -> ScheduledScreen(
                rows = state.scheduled,
                outcomes = state.scheduledOutcomes,
                outside = state.scheduledOutside,
                followUps = state.scheduledFollowUps,
                allowOverlap = state.scheduledAllowOverlap,
                onAllowOverlap = model::setScheduleOverlap,
                onOpenFollowUpChat = model::openFollowUpChat,
                onOpenOutcome = model::openScheduledOutcome,
                loading = state.scheduledLoading,
                canCreate = MobileProtocol.Capability.SCHEDULE_CREATE in
                    state.hello?.capabilities.orEmpty(),
                projects = state.snapshot?.openProjects.orEmpty(),
                vendors = NewChat.vendorOptions(state.snapshot?.rows.orEmpty(), state.hello),
                hello = state.hello,
                draft = state.drafts[DeckViewModel.SCHEDULE_DRAFT_KEY].orEmpty(),
                onDraft = { model.setDraft(DeckViewModel.SCHEDULE_DRAFT_KEY, it) },
                onRefresh = model::refreshScheduled,
                onCreate = model::createSchedule,
                habit = state.scheduleHabit,
                onHabit = model::rememberScheduleHabit,
                sources = scheduleSourcesOffer(state, model),
                onCommand = model::scheduledCommand,
                onEdit = model::openScheduleEditor,
                editId = state.scheduleEditId,
                editDetail = state.scheduleEdit,
                editDraft = state.scheduleEditId?.let { state.drafts[DeckViewModel.scheduleEditDraftKey(it)] }.orEmpty(),
                editLoading = state.scheduleEditLoading,
                editSaving = state.scheduleEditSaving,
                editError = state.scheduleEditError,
                onEditDraft = { value -> state.scheduleEditId?.let { model.setDraft(DeckViewModel.scheduleEditDraftKey(it), value) } },
                onEditDismiss = model::closeScheduleEditor,
                onEditSave = model::saveScheduleEdit,
                afterReset = state.scheduleAfterReset,
                onAfterResetDone = model::scheduleAfterResetDone,
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
                onRetryQueued = model::retryQueued,
                onEditQueued = model::editQueued,
                onDiscardQueued = model::discardQueued,
                onOpenUsage = model::openUsage,
                onLoadOrchestration = model::loadOrchestration,
                onRefreshKeepAwake = model::refreshKeepAwake,
                onKeepAwake = model::setKeepAwake,
                onRefreshNewChatDefaults = model::refreshNewChatDefaults,
                onNewChatMode = model::setNewChatMode,
                onSendLogs = model::sendLogsToDesk,
                onShareLogs = model::shareLogs,
            )
            Screen.Usage -> UsageScreen(
                report = state.usage,
                loading = state.usageLoading,
                error = state.usageError,
                onLoad = model::loadUsage,
                onFilter = model::filterUsage,
                hello = state.hello,
                onScheduleAfterReset = model::scheduleAfterReset,
                onEditSpendDefaults = model.sessionSpend::openDefaults,
                onSetActiveAccount = model::setActiveAccount,
            )
            Screen.NewChat -> {
                val target = state.newChatTarget
                val worktree by model.worktreeStart.choice.collectAsStateWithLifecycle()
                val worktreeSheet by model.worktreeManage.sheet.collectAsStateWithLifecycle()
                val reviewSheet by model.changeRequestReview.sheet.collectAsStateWithLifecycle()
                val repositorySheet by model.repositorySetup.sheet.collectAsStateWithLifecycle()
                val publishable by model.repositorySetup.publishable.collectAsStateWithLifecycle()
                NewChatScreen(
                    // A machine with nothing open has no target; the screen explains that
                    // state, so it is handed the same empty list it renders from.
                    target = target ?: dev.agentdeck.companion.data.NewChatTarget(""),
                    openProjects = if (target == null) emptyList()
                    else state.snapshot?.openProjects.orEmpty(),
                    vendors = NewChat.vendorOptions(state.snapshot?.rows.orEmpty(), state.hello),
                    hello = state.hello,
                    draft = state.drafts[NEW_CHAT_DRAFT_KEY].orEmpty(),
                    sending = state.delivering != null &&
                        state.outgoing.find(state.delivering)?.key == null,
                    notice = state.notice,
                    onTarget = model::setNewChatTarget,
                    onDraft = { model.setDraft(NEW_CHAT_DRAFT_KEY, it) },
                    onSend = model::startNewChat,
                    onDismissNotice = model::dismissNotice,
                    photos = state.pendingPhotos[NEW_CHAT_DRAFT_KEY].orEmpty(),
                    attaching = state.attaching,
                    onAttach = { bytes -> model.attachPhoto(NEW_CHAT_DRAFT_KEY, bytes) },
                    onAttachFile = { name, bytes -> model.attachFile(NEW_CHAT_DRAFT_KEY, name, bytes) },
                    onPastedText = { text -> model.attachPastedText(NEW_CHAT_DRAFT_KEY, text) },
                    onRemovePhoto = { id -> model.removePhoto(NEW_CHAT_DRAFT_KEY, id) },
                    stash = ComposerStashActions(
                        entries = ComposerStashes.entries(state.drafts, NEW_CHAT_DRAFT_KEY),
                        onStash = { model.stashDraft(NEW_CHAT_DRAFT_KEY) },
                        onRestore = { id -> model.restoreStashed(NEW_CHAT_DRAFT_KEY, id) },
                        onDiscard = { id -> model.discardStashed(NEW_CHAT_DRAFT_KEY, id) },
                    ),
                    worktree = DeckFixtureHook.paintedWorktree ?: worktree,
                    worktreeActions = WorktreeActions(
                        onPick = { on, project -> if (on) model.worktreeStart.choose(project) else model.worktreeStart.off() },
                        onName = model.worktreeStart::editName,
                        onFromHead = model.worktreeStart::setFromHead,
                        onPickExisting = model.worktreeStart::chooseExisting,
                        onSelectExisting = model.worktreeStart::pickExisting,
                        onManage = model.worktreeManage::open,
                    ),
                    repository = RepositoryButtons(
                        publishable = { project -> DeckFixtureHook.paintedPublishable || project in publishable },
                        onCheck = model.repositorySetup::check,
                        onClone = { project -> model.repositorySetup.open(project, publish = false) },
                        onPublish = { project -> model.repositorySetup.open(project, publish = true) },
                    ),
                )
                (DeckFixtureHook.paintedRepositorySheet ?: repositorySheet)?.let { sheet ->
                    RepositorySheetView(
                        sheet,
                        RepositoryActions(
                            onInput = model.repositorySetup::editInput,
                            onHost = model.repositorySetup::setHost,
                            onProtocol = model.repositorySetup::setProtocol,
                            onVisibility = model.repositorySetup::setVisibility,
                            onParent = model.repositorySetup::setParent,
                            onSubmit = model.repositorySetup::submit,
                            onOpenCloned = model.repositorySetup::openCloned,
                            onDismiss = { DeckFixtureHook.paintedRepositorySheet = null; model.repositorySetup.close() },
                        ),
                    )
                }
                val review = DeckFixtureHook.paintedReviewSheet ?: reviewSheet
                // The review opens over its worktree row and Back returns to the rows, so one sheet shows at a time.
                if (review != null) {
                    ChangeRequestReviewView(
                        review,
                        ReviewActions(
                            onReplyText = model.changeRequestReview::editReply,
                            onReply = model.changeRequestReview::reply,
                            onResolve = model.changeRequestReview::setResolved,
                            onVerdictText = model.changeRequestReview::editVerdict,
                            onSubmit = model.changeRequestReview::submit,
                            onReload = model.changeRequestReview::reload,
                            onBack = { DeckFixtureHook.paintedReviewSheet = null; model.changeRequestReview.close() },
                            onDismiss = {
                                DeckFixtureHook.paintedReviewSheet = null
                                DeckFixtureHook.paintedWorktreeSheet = null
                                model.changeRequestReview.close()
                                model.worktreeManage.close()
                            },
                            edits = model.changeRequestReview.let { flow ->
                                ReviewEditActions(
                                    onReact = flow::react,
                                    onReviewersText = flow::editReviewers,
                                    onRequestReviewers = flow::requestReviewers,
                                    onLabelsText = flow::editLabels,
                                    onLabels = flow::changeLabels,
                                    onEditRequest = flow::startRequestEdit,
                                    onRequestTitle = flow::editRequestTitle,
                                    onRequestBody = flow::editRequestBody,
                                    onCancelRequest = flow::cancelRequestEdit,
                                    onSaveRequest = flow::saveRequest,
                                    onEditComment = flow::startCommentEdit,
                                    onCommentText = flow::editCommentText,
                                    onCancelComment = flow::cancelCommentEdit,
                                    onSaveComment = flow::saveComment,
                                )
                            }.takeIf { MobileProtocol.Capability.CHANGE_REQUEST_EDITS in state.hello?.capabilities.orEmpty() },
                            onViewed = model.changeRequestReview::setViewed,
                            onStep = model.changeRequestReview::step,
                        ),
                    )
                } else (DeckFixtureHook.paintedWorktreeSheet ?: worktreeSheet)?.let { sheet ->
                    WorktreeSheetView(
                        sheet,
                        onAsk = model.worktreeManage::ask,
                        onConfirm = model.worktreeManage::confirm,
                        onBack = model.worktreeManage::dismiss,
                        onDismiss = { DeckFixtureHook.paintedWorktreeSheet = null; model.worktreeManage.close() },
                        onReview = { row: com.github.claudeagents.core.mobile.MobileWorktreeRow ->
                            row.branch?.let { model.changeRequestReview.open(sheet.projectPath, row.path, it) }
                            Unit
                        }.takeIf { MobileProtocol.Capability.CHANGE_REQUEST_REVIEW in state.hello?.capabilities.orEmpty() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Fleet(state: DeckState, model: DeckViewModel) = FleetScreen(
    snapshot = state.snapshot,
    browsingScope = state.machine?.id.orEmpty(),
    browsing = state.readingPositions.fleet,
    onBrowsing = { model.rememberBrowsing(state.machine?.id, it) },
    filter = state.filter,
    sort = state.sort,
    pins = state.pins,
    refreshing = state.refreshing,
    snoozed = state.snoozed,
    openKey = (state.screen as? Screen.Conversation)?.key,
    onFilter = model::setFilter,
    onSort = model::setSort,
    onRefresh = { model.refreshFleet() },
    onOpen = model::openConversation,
    onSnooze = model::snooze,
    onStop = { row -> model.stop(row.key, announce = "Stopped \"${row.title}\"") },
    canReview = MobileProtocol.Capability.REVIEW in state.hello?.capabilities.orEmpty(),
    onMarkReviewed = { row -> model.markReviewed(row.key, emptyList(), reviewed = true) },
    canOrganize = MobileProtocol.Capability.SESSION_ACTIONS in state.hello?.capabilities.orEmpty(),
    onSessionAction = model::sessionAction,
    canShare = MobileProtocol.Capability.SESSION_EXPORT in state.hello?.capabilities.orEmpty(),
    onShare = model::shareConversation,
    canDelete = MobileProtocol.Capability.SESSION_DELETE in state.hello?.capabilities.orEmpty(),
    onDelete = model::previewDelete,
    deletePreview = state.deletePreview,
    onConfirmDelete = model::confirmDelete,
    onDismissDelete = model::dismissDelete,
    canSearchMessages = MobileProtocol.Capability.SESSION_SEARCH in state.hello?.capabilities.orEmpty(),
    messageSearch = state.messageSearch,
    onSearchMessages = model::searchMessages,
    onSearchMoreMessages = model::searchMoreMessages,
    canFolder = MobileProtocol.Capability.SESSION_FOLDERS in state.hello?.capabilities.orEmpty(),
    onFile = model::fileInFolder,
    canEditFolders = MobileProtocol.Capability.FOLDER_ACTIONS in state.hello?.capabilities.orEmpty(),
    onFolderAction = model::folderAction,
    canFork = MobileProtocol.Capability.SESSION_FORK in state.hello?.capabilities.orEmpty(),
    onFork = model::startFork,
    forkPoints = state.forkPoints,
    onForkAt = model::forkAt,
    onDismissFork = model::dismissFork,
    canBranch = MobileProtocol.Capability.SESSION_BRANCH in state.hello?.capabilities.orEmpty(),
    onBranch = model::branchChat,
    canPinOrder = MobileProtocol.Capability.PIN_ORDER in state.hello?.capabilities.orEmpty(),
    onMovePin = model::movePin,
    canRetitle = MobileProtocol.Capability.SESSION_RETITLE in state.hello?.capabilities.orEmpty(),
    onRetitle = model::retitle,
    canRewind = MobileProtocol.Capability.SESSION_REWIND in state.hello?.capabilities.orEmpty(),
    onRewind = { row -> model.rewind.open(row.key) },
    canCommitStaged = MobileProtocol.Capability.COMMIT_STAGED in state.hello?.capabilities.orEmpty(),
    onCommitStaged = { row -> model.commitStaged.open(row.key, row.title) },
    canContext = MobileProtocol.Capability.CONTEXT_BREAKDOWN in state.hello?.capabilities.orEmpty(),
    onContext = { row -> model.context.open(row.key, row.title) },
    canDirs = MobileProtocol.Capability.SESSION_DIRS in state.hello?.capabilities.orEmpty(),
    onDirs = { row -> model.sessionDirs.open(row.key, row.title) },
    canSpend = MobileProtocol.Capability.SESSION_SPEND in state.hello?.capabilities.orEmpty(),
    onSpend = { row -> model.sessionSpend.open(row.key, row.title) },
    canHandoff = MobileProtocol.Capability.SESSION_HANDOFF in state.hello?.capabilities.orEmpty(),
    claudeAccounts = state.hello?.accounts?.get(com.github.claudeagents.core.AgentVendor.CLAUDE).orEmpty(),
    onHandoffStart = model::startHandoff,
    handoffKey = state.handoffKey,
    onDismissHandoff = model::dismissHandoff,
    onHandoff = { row, account -> model.continueOnAccount(row, account.id, account.label) },
)

/** The four destinations, and the badges on the ones that show what they count. */
/** "After sessions finish…" for a create dialog, or null when this machine cannot hold a prompt for other sessions. */
private fun scheduleSourcesOffer(state: DeckState, model: DeckViewModel): ScheduleSourcesOffer? =
    ScheduleSourcesOffer(state.scheduleSources, model::loadScheduleSources)
        .takeIf { MobileProtocol.Capability.SCHEDULE_DEPENDENCIES in state.hello?.capabilities.orEmpty() }

@Composable
private fun DeckNavigationBar(state: DeckState, model: DeckViewModel) {
    val labelled = Navigation.labelsBar(LocalDensity.current.fontScale)
    NavigationBar {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = state.destination == destination,
                onClick = { onDestination(destination, model) },
                icon = { DestinationIcon(destination, state) },
                label = if (labelled) { { Text(destination.label, maxLines = 1) } } else null,
            )
        }
    }
}

@Composable
private fun DeckNavigationRail(state: DeckState, model: DeckViewModel) {
    // Centred while the four fit; scrolling once they do not. A phone on its side at 200 % text
    // squeezed each item into what was left, so labels overprinted the next icon and Workspace
    // fell off the bottom (J14, M2JourneysE2eTest).
    NavigationRail {
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Destination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = state.destination == destination,
                        onClick = { onDestination(destination, model) },
                        icon = { DestinationIcon(destination, state) },
                        label = { Text(destination.label) },
                    )
                }
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
        Destination.REVIEW -> Icons.Filled.CheckCircle
        Destination.SCHEDULED -> Icons.Filled.DateRange
        Destination.SETTINGS -> Icons.Filled.Settings
    }
    // Less what the user has already swiped aside: a badge of 3 over a list showing 1 is
    // the app arguing with itself.
    val badge = when (destination) {
        Destination.FLEET ->
            Snooze.badge(state.snapshot?.badgeCount ?: 0, state.snoozed, state.snapshot?.rows.orEmpty())
        Destination.REVIEW -> ReviewQueue.count(state.snapshot?.rows.orEmpty())
        else -> 0
    }
    val counted = if (destination == Destination.REVIEW) "to review" else "waiting on you"
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
                "${destination.label}, $badge $counted"
            } else {
                destination.label
            },
        )
    }
}

private fun onDestination(destination: Destination, model: DeckViewModel) {
    model.go(destination)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckTopBar(state: DeckState, model: DeckViewModel) {
    TopAppBar(
        title = {
            Column {
                Text(
                    when (val screen = state.screen) {
                        is Screen.Conversation -> screen.title.ifBlank { "Conversation" }
                        Screen.Scheduled -> "Schedule"
                        Screen.NewChat -> "New chat"
                        Screen.Review -> "Review"
                        Screen.Settings -> "Workspace"
                        Screen.Usage -> "Usage"
                        else -> "Chats"
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
                    conversationSubtitle(state.transcript?.takeIf { it.key == state.screen.key })?.let {
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
            containerColor = MaterialTheme.colorScheme.background,
        ),
        navigationIcon = {
            if (Navigation.backTarget(state.screen, state.backStack) != null) {
                IconButton(onClick = { model.back() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            if (state.screen is Screen.Conversation) {
                // Progressive disclosure: one prompt is the conversation's title, already in
                // this bar. The action appears once there are two chapters to choose between.
                val turns = state.transcript?.takeIf { it.key == state.screen.key }?.turns.orEmpty()
                if (ConversationOutline.offers(turns)) {
                    dev.agentdeck.companion.ui.DeckIconButton("Outline of this chat",
                        dev.agentdeck.companion.ui.OutlineIcon, { model.outlineConversation(true) })
                }
                dev.agentdeck.companion.ui.DeckIconButton("Find in this chat", Icons.Filled.Search,
                    { model.findInConversation(true) })
            }
            if (Navigation.showsBar(state.screen)) {
                state.machine?.let { active ->
                    MachinePicker(
                        machines = state.machines,
                        active = active,
                        onSwitch = model::switchMachine,
                        onAdd = model::addMachine,
                    )
                }
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
                    "Android's settings — and you can turn them off in Workspace › Alerts.",
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
