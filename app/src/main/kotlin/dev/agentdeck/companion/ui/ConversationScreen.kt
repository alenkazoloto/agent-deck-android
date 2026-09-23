package dev.agentdeck.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.agentdeck.companion.data.ConversationOutline
import dev.agentdeck.companion.data.EmojiDraft
import dev.agentdeck.companion.data.MentionDraft
import dev.agentdeck.companion.data.MentionMatches
import com.github.claudeagents.core.mobile.MobileIssueList
import com.github.claudeagents.core.mobile.MobileIssueQuery
import dev.agentdeck.companion.data.PendingPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.composer.EmojiCompletion
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobileCommandQuery
import com.github.claudeagents.core.mobile.MobileDeskCommands
import com.github.claudeagents.core.mobile.MobileDeskCommands.asCommand
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileTodo
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileToolResult
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.Dictation
import dev.agentdeck.companion.data.OutgoingSend

/** P6's set, verbatim: the four replies that carry most couch-side traffic. */
private val QUICK_REPLIES = linkedMapOf(
    "Summarize" to "Summarize what changed, what is left, and anything you need from me.",
    "Test changes" to "Run the relevant tests and report any failures.",
    "Review" to "Review the changes for bugs and risks. Explain what needs attention.",
    "Continue" to "Continue with the next step.",
)

/** The gutter an agent bubble leaves for its avatar, so a block's bubbles stay left-aligned. */
private val AVATAR_SIZE = 30.dp
private val AVATAR_GUTTER = 38.dp

internal fun streamingTurnLabel(turn: MobileTurn): String? =
    "Still writing…".takeIf { turn.role == "assistant" && turn.streaming }

@Composable
fun ConversationScreen(
    target: Screen.Conversation,
    page: MobileTranscriptPage?,
    loading: Boolean,
    /** The page came off the disk, not off the wire — the marker says so, in its own age. */
    cached: Boolean,
    draft: String,
    notice: String?,
    onDraft: (String) -> Unit,
    onSend: (text: String, stopFirst: Boolean) -> Unit,
    onStop: () -> Unit,
    onDismissNotice: () -> Unit,
    /** Stop was tapped and the machine has not answered yet. */
    stopping: Boolean = false,
    /** What this conversation still owes the machine; empty is the ordinary case. */
    queued: List<OutgoingSend> = emptyList(),
    delivering: String? = null,
    onRetryQueued: (String) -> Unit = {},
    onEditQueued: (String) -> Unit = {},
    onDiscardQueued: (String) -> Unit = {},
    /** The machine advertises `/v1/answer`; without it a question is read-only here. */
    canAnswer: Boolean = false,
    onAnswer: (askId: String, answers: Map<String, String>) -> Unit = { _, _ -> },
    /** Bumped by each answer the machine did not take, so a card's sent lock lifts for a retry. */
    answerFailures: Int = 0,
    readingScope: String = "",
    readingAnchor: dev.agentdeck.companion.data.ReadingAnchor? = null,
    onReadingAnchor: (dev.agentdeck.companion.data.ReadingAnchor) -> Unit = {},
    canPage: Boolean = false,
    earlierLoading: Boolean = false,
    earlierError: String? = null,
    earlierExpired: Boolean = false,
    onEarlier: () -> Unit = {},
    onRefreshHistory: () -> Unit = {},
    hasPendingLatest: Boolean = false,
    onAcceptLatest: () -> Unit = {},
    findOpen: Boolean = false,
    onCloseFind: () -> Unit = {},
    /** The outline sheet, hoisted exactly as Find is: the view model clears it on a new chat. */
    outlineOpen: Boolean = false,
    onOutline: (Boolean) -> Unit = {},
    /** Re-ask the machine for this conversation — the pull gesture, and nothing else. */
    onRefresh: () -> Unit = {},
    /** A reload this reader pulled for. The view model owns it: only that side knows a pull
     *  with no link never reached the machine, so the spinner never outlives its request. */
    refreshing: Boolean = false,
    /** Open New chat carrying a past prompt of this conversation, verbatim. */
    onStartFromPrompt: (String) -> Unit = {},
    hello: MobileHello? = null,
    /** What the next send names — see `DeckState.composerSelection`. */
    selection: MobileRunSelection = MobileRunSelection(null, null, null),
    onPick: (ComposerPicks.Field, String?) -> Unit = { _, _ -> },
    /** The larger body of the call whose sheet is open, once the reader asked for it. */
    toolOutput: MobileToolResult? = null,
    toolOutputCallId: String? = null,
    toolOutputLoading: Boolean = false,
    toolOutputError: String? = null,
    onLoadToolOutput: (callId: String) -> Unit = {},
    onCloseToolOutput: () -> Unit = {},
    /**
     * The Changes half of this screen. Absent capability means absent tab: a machine that does
     * not serve `/v1/review` cannot answer the first tap, and a disabled tab is a promise the
     * app has no way to keep.
     */
    /** Photos already uploaded for this conversation; the machine advertises `attachments`. */
    photos: List<PendingPhoto> = emptyList(),
    attaching: Boolean = false,
    onAttach: (ByteArray) -> Unit = {},
    onRemovePhoto: (String) -> Unit = {},
    /** The `@` popup's query, or a lambda that never runs when `files` is unadvertised. */
    onSearchFiles: suspend (String) -> MentionMatches = { MentionMatches() },
    /** The `#` popup's query; unused when `issues` is unadvertised. */
    onSearchIssues: suspend (String) -> MobileIssueList = { MobileIssueList("", it, emptyList()) },
    /** The `/`/`$` popup's catalogue, read once per conversation; unused when `commands` is unadvertised. */
    onLoadCommands: suspend () -> List<MobileCommand>? = { null },
    /** "Earlier prompts", read each time the sheet opens; unused when `prompt-history` is unadvertised. */
    onLoadPrompts: suspend () -> List<String>? = { null },
    /** Settings › Writing › "Complete emoji after :". */
    emojiCompletion: Boolean = true,
    /** Null in fixtures that do not exercise it; production always passes the conversation's stash. */
    stash: ComposerStashActions? = null,
    canReview: Boolean = false,
    /**
     * Which half is showing. Hoisted rather than kept here because it is the app's own state:
     * a conversation opens on its messages — that is what the row that opened it was about —
     * and the view model is what clears it when another conversation takes the screen.
     */
    changesOpen: Boolean = false,
    onChangesOpen: (Boolean) -> Unit = {},
    review: MobileReviewList? = null,
    reviewLoading: Boolean = false,
    reviewError: String? = null,
    reviewMarking: Boolean = false,
    reviewPath: String? = null,
    reviewDiff: MobileReviewFileDiff? = null,
    reviewDiffLoading: Boolean = false,
    reviewDiffError: String? = null,
    diffSoftWrap: Boolean = false,
    diffLineNumbers: Boolean = false,
    onOpenChanges: () -> Unit = {},
    onOpenReviewFile: (String) -> Unit = {},
    onCloseReviewFile: () -> Unit = {},
    onMarkReviewed: (paths: List<String>, reviewed: Boolean) -> Unit = { _, _ -> },
    /**
     * What this chat waits on, from its fleet row, while the plugin says it is waiting on you.
     * The row named it since 1.37; the chat itself still said "working…" over a blocked run.
     */
    waiting: String? = null,
    /** The chat's account is at its usage limit and the machine can queue a continuation. */
    limitContinuation: LimitContinuationOffer? = null,
    /** The machine can queue a prompt for later; the composer's text then schedules into this chat. */
    scheduleIntoChat: ScheduleIntoChatOffer? = null,
    /** A desk-only command that leaves this screen: New chat, Usage, Workspace or Chats. */
    onDeskNavigation: (MobileDeskCommands.Action) -> Unit = {},
    /** A desk command's one-line outcome ("No running turn to stop."), as a snackbar. */
    onCommandNotice: (String) -> Unit = {},
) {
    val composerFocus = remember { FocusRequester() }
    val running = page?.running == true
    val body = conversationBody(page, loading || earlierLoading)
    val turns = page?.turns.orEmpty()
    val turnCount = turns.size
    val scope = rememberCoroutineScope()
    val offersEarlier = canPage && page?.previousCursor != null
    val truncation = if (offersEarlier) null else TranscriptTail.truncationNotice(page)
    val leadingItems = if (offersEarlier || truncation != null) 1 else 0
    val reading = rememberConversationReading(readingScope, target.key, page, leadingItems, readingAnchor, onReadingAnchor)
    val listState = reading.listState
    val find = rememberConversationFind(readingScope + target.key, turns, findOpen)
    var revealedMatch by remember(readingScope, target.key) {
        mutableStateOf<dev.agentdeck.companion.data.ConversationMatch?>(null)
    }
    // The call whose output is open. Plain `remember`: a sheet is not worth restoring across a
    // process death, and reopening it would put a modal over a conversation nobody navigated to.
    var openCall by remember(readingScope, target.key) { mutableStateOf<MobileToolCall?>(null) }
    val outline = remember(turns) { ConversationOutline.of(turns) }
    // Hoisted out of the run line, which shows only while a draft exists: a typed `/model`
    // empties the composer as it opens this.
    var runSheetOpen by rememberSaveable(target.key) { mutableStateOf(false) }
    var continuing by rememberSaveable(target.key) { mutableStateOf(false) }
    var scheduling by rememberSaveable(target.key) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val deskActions = buildSet {
        add(MobileDeskCommands.Action.STOP)
        add(MobileDeskCommands.Action.NEW_CHAT)
        add(MobileDeskCommands.Action.COPY_LAST_RESPONSE)
        add(MobileDeskCommands.Action.SETTINGS)
        add(MobileDeskCommands.Action.CHATS)
        if (RunChoiceSummary.offered(hello)) add(MobileDeskCommands.Action.RUN_SETTINGS)
        if (canReview) add(MobileDeskCommands.Action.CHANGES)
        if (MobileProtocol.Capability.USAGE in hello?.capabilities.orEmpty()) add(MobileDeskCommands.Action.USAGE)
        if (scheduleIntoChat != null) add(MobileDeskCommands.Action.SCHEDULE)
    }
    // The desk's refusals keep the typed line (`SlashCommandRoutes`), and so do these.
    val runDeskCommand: (MobileDeskCommands.Action) -> Boolean = { action ->
        when (action) {
            MobileDeskCommands.Action.RUN_SETTINGS -> true.also { runSheetOpen = true }
            MobileDeskCommands.Action.SCHEDULE -> true.also { scheduling = true }
            MobileDeskCommands.Action.STOP -> if (running) {
                onStop()
                true
            } else {
                onCommandNotice("No running turn to stop.")
                false
            }
            MobileDeskCommands.Action.CHANGES -> true.also {
                onChangesOpen(true)
                if (review == null && !reviewLoading) onOpenChanges()
            }
            MobileDeskCommands.Action.COPY_LAST_RESPONSE -> {
                val text = MobileDeskCommands.lastResponse(turns)
                if (text == null) {
                    onCommandNotice("No response to copy yet.")
                } else {
                    clipboard.setText(AnnotatedString(text))
                    onCommandNotice("Copied the last response.")
                }
                text != null
            }
            else -> true.also { onDeskNavigation(action) }
        }
    }
    // `firstVisibleItemIndex` is 0 before anything is laid out as well as at the top of the
    // list, and the two are indistinguishable here — so the control is *hidden* in the
    // ambiguous case rather than offered over an empty viewport (`memory/rendering.md`).
    val hasOlder by remember(reading) {
        derivedStateOf { reading.listState.firstVisibleItemIndex > 0 }
    }
    val viewportHeight by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(find.activeMatch) {
        revealedMatch = null
        find.activeMatch?.let { match ->
            androidx.compose.runtime.withFrameNanos { }
            kotlinx.coroutines.yield()
            val index = turns.indexOfFirst { it.id == match.turnId }
            if (index >= 0) {
                reading.showTurn(index)
                revealedMatch = match
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Only over turns already on screen — that is a quiet refresh, and a bar is the
        // honest word for it. A first load has no layout to reassure anyone about, so it gets
        // the shape of the answer instead (TranscriptSkeleton).
        // Never beside the pull spinner: one reload, one indicator.
        if (body is ConversationBody.Loading && turns.isNotEmpty() && !refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (cached && page != null) {
            TranscriptMarker("Saved copy · as of ${Times.clock(page.generatedAtMs, LocalNow.current())}")
        }

        ConversationFindBar(find, onCloseFind)

        if (canReview) {
            ConversationTabs(
                changesOpen = changesOpen,
                changesLabel = changesTabLabel(review),
                onMessages = { onChangesOpen(false) },
                onChanges = {
                    onChangesOpen(true)
                    if (review == null && !reviewLoading) onOpenChanges()
                },
            )
        }

        if (changesOpen && canReview) {
            // The composer is deliberately not painted here: a reader on the Changes half is
            // judging, not replying, and a text field under a diff is a send one scroll away
            // from the checkbox it was aiming at.
            ChangesTab(
                review = review,
                loading = reviewLoading,
                error = reviewError,
                marking = reviewMarking,
                openPath = reviewPath,
                diff = reviewDiff,
                diffLoading = reviewDiffLoading,
                diffError = reviewDiffError,
                softWrap = diffSoftWrap,
                lineNumbers = diffLineNumbers,
                onOpenFile = onOpenReviewFile,
                onCloseFile = onCloseReviewFile,
                onMark = onMarkReviewed,
                modifier = Modifier.weight(1f),
            )
        } else {

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("conversation-transcript"),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            ) {
                if (offersEarlier) {
                    item(key = "transcript-earlier") {
                        EarlierHistory(earlierLoading, earlierError, earlierExpired, onEarlier, onRefreshHistory, page?.historyPending == true)
                    }
                } else truncation?.let { marker ->
                    item(key = "transcript-truncated") { TranscriptMarker(marker) }
                }

                if (body is ConversationBody.Loading && turns.isEmpty()) {
                    item(key = "transcript-skeleton") { TranscriptSkeleton() }
                }

                itemsIndexed(turns, key = { _, turn -> turn.id }) { index, turn ->
                    val match = find.activeMatch?.takeIf { it.turnId == turn.id }
                    if (match != null) ConversationFindPassage(turn, match, reveal = revealedMatch == match, viewportHeight = viewportHeight)
                    else TurnBubble(
                        turn = turn,
                        vendor = target.vendor,
                        generatedAtMs = page?.generatedAtMs ?: 0L,
                        runLive = running,
                        first = TurnGrouping.startsBlock(turns, index),
                        last = TurnGrouping.endsBlock(turns, index),
                        stamped = TurnGrouping.carriesTime(turns, index),
                        canAnswer = canAnswer,
                        onAnswer = onAnswer,
                        answerFailures = answerFailures,
                        onOpenCall = { call ->
                            openCall = call
                            // Nothing on the page to show first, so the one request is the open.
                            if (call.result == null) onLoadToolOutput(call.id)
                        },
                        canFetchResults = MobileProtocol.Capability.TOOL_RESULTS in hello?.capabilities.orEmpty(),
                    )
                }

                if (running) {
                    item(key = "working") { WorkingBubble(target.vendor, page?.liveLine, stopping, onStop, waiting) }
                } else if (waiting != null) {
                    item(key = "waiting") { TranscriptMarker(waiting) }
                }
                if (!running && limitContinuation != null) {
                    item(key = "limit-continuation") {
                        LimitContinuationMarker(limitContinuation.accountLabel, limitContinuation.reset) { continuing = true }
                    }
                }
                // Only for a page that really arrived carrying nothing — never for a page that
                // failed to arrive, which is [ConversationBody.Unavailable] and is explained by
                // the link banner above rather than contradicted down here.
                if (body is ConversationBody.Empty) {
                    item(key = "transcript-empty") {
                        ConversationEmpty(target.vendor) { composerFocus.requestFocus() }
                    }
                }
            }

            val unread = TranscriptTail.unreadBelow(turnCount, reading.readThrough)
            // Two jumps, one strip: the ends of the loaded transcript are two different
            // destinations, so they are two controls rather than one that changes meaning.
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                JumpTo(
                    visible = !findOpen && hasOlder,
                    label = "Oldest loaded message",
                    icon = Icons.Filled.KeyboardArrowUp,
                ) {
                    scope.launch {
                        onCloseFind()
                        reading.oldest()
                    }
                }
                JumpTo(
                    visible = !findOpen && (!reading.atTail || hasPendingLatest),
                    label = when (unread) {
                        0 -> "Latest message"
                        1 -> "Latest message, 1 new turn"
                        else -> "Latest message, $unread new turns"
                    },
                    icon = Icons.Filled.KeyboardArrowDown,
                ) {
                    scope.launch {
                        onCloseFind()
                        reading.latest()
                        onAcceptLatest()
                    }
                }
            }
        }

        notice?.let { message -> NoticeBar(message, onDismissNotice) }

        Composer(
            draft = draft,
            running = running,
            queued = queued,
            delivering = delivering,
            onRetryQueued = onRetryQueued,
            onEditQueued = onEditQueued,
            onDiscardQueued = onDiscardQueued,
            fieldFocus = composerFocus,
            onDraft = onDraft,
            onSend = onSend,
            pills = {
                ComposerRunPills(hello, target.vendor, selection, onOpen = { runSheetOpen = true })
            },
            photos = photos,
            attaching = attaching,
            canAttach = MobileProtocol.Capability.ATTACHMENTS in hello?.capabilities.orEmpty(),
            onAttach = onAttach,
            onRemovePhoto = onRemovePhoto,
            // A lambda the popup never calls is how "absent capability, absent surface" is
            // spelled here: null is what `Composer` checks, so no gating lives in two places.
            onSearchFiles = onSearchFiles.takeIf {
                MobileProtocol.Capability.FILES in hello?.capabilities.orEmpty()
            },
            onSearchIssues = onSearchIssues.takeIf {
                MobileProtocol.Capability.ISSUES in hello?.capabilities.orEmpty()
            },
            commandScope = target.key,
            commandVendor = target.vendor,
            onLoadCommands = onLoadCommands.takeIf {
                MobileProtocol.Capability.COMMANDS in hello?.capabilities.orEmpty()
            },
            onLoadPrompts = onLoadPrompts.takeIf {
                MobileProtocol.Capability.PROMPT_HISTORY in hello?.capabilities.orEmpty()
            },
            emojiCompletion = emojiCompletion,
            stash = stash,
            deskActions = deskActions,
            onDeskCommand = runDeskCommand,
            onSchedule = if (scheduleIntoChat != null) ({ scheduling = true }) else null,
        )
        }
    }
    if (runSheetOpen) {
        RunSettingsSheet(hello, target.vendor, selection, onPick, onDismiss = { runSheetOpen = false })
    }
    // The offer can go (the reset passed, the chat ran again) while its dialog is up; so does the dialog.
    if (continuing && limitContinuation != null) {
        LimitContinuationDialog(
            reset = limitContinuation.reset,
            prompt = limitContinuation.prompt,
            onPrompt = limitContinuation.onPrompt,
            onDismiss = { continuing = false },
            onSchedule = {
                continuing = false
                limitContinuation.onSchedule(limitContinuation.reset.dueAtMs())
            },
        )
    }
    if (scheduling && scheduleIntoChat != null) {
        ScheduleIntoChatDialog(
            offer = scheduleIntoChat,
            hello = hello,
            vendor = target.vendor,
            projectPath = target.projectPath,
            prompt = draft,
            onPrompt = onDraft,
            onDismiss = { scheduling = false },
        )
    }
    if (outlineOpen && outline.offered) {
        OutlineSheet(
            outline = outline,
            onDismiss = { onOutline(false) },
            onJump = { entry ->
                onOutline(false)
                scope.launch { reading.showTurn(entry.turnIndex) }
            },
            onStartFrom = { prompt ->
                onOutline(false)
                onStartFromPrompt(prompt)
            },
        )
    }
    openCall?.let { call ->
        ToolDetailSheet(
            call = call,
            // Only this call's larger body: a fetch still in the air for the previous one must
            // not paint its output under a title that names a different tool.
            full = toolOutput?.takeIf { toolOutputCallId == call.id },
            loading = toolOutputLoading,
            error = toolOutputError,
            onLoadFull = { onLoadToolOutput(call.id) },
            onDismiss = {
                openCall = null
                onCloseToolOutput()
            },
        )
    }
}

/** A centred, quiet line about the list itself — never a bubble, because nobody said it. */
@Composable
private fun TranscriptMarker(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The shape of a conversation, while its first page is still on the wire.
 *
 * Static on purpose. The usual skeleton shimmers, and a shimmer is an `infiniteRepeatable`
 * that by construction never settles — a Roborazzi capture waits for the composition to go
 * idle, so the golden that holds this state would hang the suite rather than fail it
 * (`GoldenScreenshotTest`, which keeps live runs out for the same reason).
 */
@Composable
private fun TranscriptSkeleton() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // One announcement for the whole placeholder — the bars under it say nothing.
            .clearAndSetSemantics { contentDescription = "Loading this conversation" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkeletonTurn(user = false, lines = 3)
        SkeletonTurn(user = true, lines = 1)
        SkeletonTurn(user = false, lines = 2)
    }
}

/** One placeholder bubble, at the geometry [TurnBubble] will paint into. */
@Composable
private fun SkeletonTurn(user: Boolean, lines: Int) {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        if (!user) {
            Box(Modifier.size(AVATAR_SIZE).clip(CircleShape).background(tone))
            Spacer(Modifier.width(AVATAR_GUTTER - AVATAR_SIZE))
        }
        Surface(
            modifier = if (user) Modifier.width(220.dp) else Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = tone,
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(lines) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth(if (index == lines - 1) 0.6f else 1f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
                    )
                }
            }
        }
    }
}

/**
 * A conversation that really arrived carrying nothing, and the one gesture that ends it.
 *
 * It was a single grey line centred in a blank column, which reads as a screen that failed
 * rather than one waiting to be written in — and it named no way out, though the field that
 * resolves it is two rows below.
 */
@Composable
private fun ConversationEmpty(vendor: AgentVendor, onWrite: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Create,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            "Nothing in this conversation yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            "Ask ${vendor.label()} to investigate, make a change, or explain the next step.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onWrite, modifier = Modifier.padding(top = 8.dp)) {
            Text("Write the first message")
        }
    }
}

/** One end-of-transcript jump. The label is the tooltip and the accessible name both. */
@Composable
private fun JumpTo(
    visible: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
            DeckIconButton(label = label, icon = icon, onClick = onClick)
        }
    }
}

/**
 * The agent's live state as a bubble on its own side of the column, with the interrupt on it.
 *
 * "Stop" used to sit in a permanent third row under the composer. It belongs here: this is the
 * row a reader is already watching to decide whether to interrupt, and putting the control
 * anywhere else asks them to look at one thing and act on another.
 */
@Composable
private fun WorkingBubble(
    vendor: AgentVendor,
    liveLine: String?,
    stopping: Boolean,
    onStop: () -> Unit,
    waiting: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VendorAvatar(vendor)
        Spacer(Modifier.width(AVATAR_GUTTER - AVATAR_SIZE))
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The viewed conversation's own vendor voice — a Codex run saying
                        // "Claude is working…" is the desktop's mistake to not repeat.
                        Text(
                            when {
                                stopping -> "Stopping…"
                                waiting != null -> waiting
                                else -> vendor.workingText()
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        // A blocked run is not busy: the spinner would say the agent is still
                        // getting on with it while it sits on the reader's decision.
                        if (waiting == null || stopping) RunningIndicator(Modifier.padding(start = 6.dp))
                    }
                    // The live tool ticker as the plugin already phrased it.
                    liveLine?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DeckIconButton("Stop", DeckIcons.Stop, onClick = onStop, enabled = !stopping,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** Keep the provider visible as the same identity icon used by the plugin's session list. */
@Composable
private fun VendorAvatar(vendor: AgentVendor, visible: Boolean = true) {
    Box(Modifier.size(AVATAR_SIZE), contentAlignment = Alignment.Center) {
        if (visible) AgentIdentity(vendor)
    }
}

/**
 * One bubble in a block of them.
 *
 * The role is carried by **alignment and tonal colour** — the modern chat pattern, and the one
 * that stops a phone-sized column of identical full-width cards from reading as one wall.
 * [first] and [last] are the block's ends ([TurnGrouping]): the avatar and the agent's name go
 * on the first bubble, the tail corner on the last, and everything between is drawn as
 * continuation so a long answer reads as one utterance rather than six. [stamped] is the
 * narrower question of which turn writes the time — one per run of them, not one per block.
 */
@Composable
private fun TurnBubble(
    turn: MobileTurn,
    vendor: AgentVendor,
    generatedAtMs: Long,
    runLive: Boolean,
    first: Boolean,
    last: Boolean,
    stamped: Boolean,
    canAnswer: Boolean = false,
    onAnswer: (askId: String, answers: Map<String, String>) -> Unit = { _, _ -> },
    answerFailures: Int = 0,
    onOpenCall: (MobileToolCall) -> Unit = {},
    canFetchResults: Boolean = false,
) {
    val user = turn.role == "user"
    val live = streamingTurnLabel(turn) != null
    val big = 20.dp
    val tail = 6.dp
    // The tail corner is what closes a block, so a turn still being written cannot wear it.
    val closed = last && !live
    val shape = if (user) {
        RoundedCornerShape(big, big, if (closed) tail else big, big)
    } else {
        RoundedCornerShape(big, big, big, if (closed) tail else big)
    }
    Column(
        Modifier.fillMaxWidth().padding(
            start = 12.dp,
            end = 12.dp,
            top = if (first) 8.dp else 2.dp,
            bottom = 0.dp,
        ),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            Surface(
                // A user bubble stops short of the edge so the two sides are told apart at a
                // glance even in one-sided stretches; an agent turn carries code and checklists
                // and gets the width.
                modifier = if (user) Modifier.widthIn(max = 320.dp) else Modifier.weight(1f),
                shape = shape,
                color = if (user) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
                contentColor = if (user) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
                // Deliberately a static outline and not a second set of dots: a Roborazzi
                // capture waits for the composition to go idle, so an `infiniteRepeatable`
                // here would hang the golden that is meant to hold this state.
                border = if (live) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // Only on the block's first bubble, and only for a speaker whose name is
                    // not already given by which side the bubble is on.
                    if (!user && first) {
                        Text(
                            if (turn.role == "assistant") vendor.label()
                            else turn.role.replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    turn.thought?.takeIf { it.isNotBlank() }?.let { ThoughtRow(it) }
                    if (turn.text.isNotBlank()) {
                        // Selectable, so a sentence can be copied out with the platform's own
                        // long-press toolbar. Nothing in the app could select a word before this,
                        // and the only copy route in a conversation was a code block's button.
                        SelectionContainer {
                            if (user) {
                                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                MarkdownText(turn.text)
                            }
                        }
                    }
                    if (turn.todos.isNotEmpty()) TaskChecklist(turn.todos)
                    // Hoisted out of the group below: a question is the one tool call that is
                    // asking the reader for something — see [TranscriptQuestions].
                    TranscriptQuestions.asked(turn.toolCalls).forEach { call ->
                        QuestionCard(call, canAnswer, answerFailures) { onAnswer(call.id, it) }
                    }
                    val otherCalls = TranscriptQuestions.otherCalls(turn.toolCalls)
                    if (otherCalls.isNotEmpty()) ToolCallGroup(otherCalls, runLive, onOpenCall, turn.groupTitle, canFetchResults)
                    // Deliberately no TypingDots: this bubble sits directly above the working
                    // bubble, which already animates a set — two stacked is noise. What the
                    // reader who scrolled up needs is a caption saying the text stops here
                    // because it is still arriving, not a second live indicator.
                    streamingTurnLabel(turn)?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (stamped) {
            // The turn was stamped by the machine, so it is read against the page the
            // machine sent it on — not against the phone's idea of what day it is.
            Text(
                Times.clock(turn.timestampMs, generatedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = if (user) 0.dp else AVATAR_GUTTER + 6.dp,
                    end = if (user) 6.dp else 0.dp,
                    top = 3.dp,
                    bottom = 4.dp,
                ),
            )
        }
    }
}

/**
 * The turn's checklist, **outside** the collapsed tool-call group and expanded by default.
 *
 * Task progress was on the wire nowhere and on screen nowhere: a TodoWrite reached the phone as
 * one anonymous row inside "Tool calls (25)", so the plan the desktop paints as a checklist was
 * the one thing a reader on the couch could not see. It is the answer to "what is this agent
 * doing", which is the whole reason to open the app — so it is never what a tap has to reveal.
 */
@Composable
private fun TaskChecklist(todos: List<MobileTodo>) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val done = todos.count { it.done }
    Surface(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Tasks  ${MobileTodo.progress(todos)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide the task list" else "Show the task list",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { if (todos.isEmpty()) 0f else done.toFloat() / todos.size },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape),
                // The track defaults to `secondaryContainer`, which in this scheme is the
                // green that means "Codex" everywhere else — a progress bar reading half
                // blue, half green looks like two measurements rather than one. `outlineVariant`
                // rather than a surface role because the track sits *on* a surface: the two
                // surface tones are a shade apart in dark, so the unfilled part disappeared
                // and the bar read as having no remainder at all.
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            if (expanded) {
                Column(
                    Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    todos.forEach { todo ->
                        TaskLine(
                            when {
                                todo.done -> TaskMark.DONE
                                todo.active -> TaskMark.ACTIVE
                                else -> TaskMark.PENDING
                            },
                            InlineMarkdown.inline(todo.text),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsed until the reader opens it — see [ToolDisclosure] for why a live run is not a
 * reason to open one.
 *
 * `rememberSaveable`, not `remember`: this runs inside a `LazyColumn` item, whose composition
 * is discarded once it scrolls out of the buffer. A reader who tidied a group away got it back
 * open by scrolling past it and back, which is indistinguishable from it never having closed.
 */
@Composable
private fun ToolCallGroup(
    calls: List<MobileToolCall>,
    runLive: Boolean,
    onOpenCall: (MobileToolCall) -> Unit = {},
    title: String? = null,
    canFetchResults: Boolean = false,
) {
    var readerOpened by rememberSaveable { mutableStateOf(false) }
    val expanded = ToolDisclosure.expanded(readerOpened, runLive)
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { readerOpened = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title ?: "Tool calls (${calls.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                // A Codex title runs to 120 characters; the chevron has to stay on screen.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide these tool calls" else "Show these tool calls",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            calls.forEach { call ->
                // Only a call that returned something opens: a row that opened an empty sheet
                // would be advertising a surface the machine has not filled. A finished call
                // whose output the page left out also opens where the machine can serve it: the
                // desk trims older outputs to fit the page, and the row otherwise looked like
                // one with its output a tap away and did nothing (J04). An older machine's
                // absence stays unmarked (`MobileToolCall.result`).
                val opens = call.result != null || (canFetchResults && call.status != MobileToolCall.RUNNING)
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (opens) Modifier.clickable { onOpenCall(call) } else Modifier)
                        .padding(top = 6.dp),
                ) {
                    Text(
                        when (call.status) {
                            MobileToolCall.RUNNING -> "◌ "
                            MobileToolCall.ERROR -> "✗ "
                            else -> "✓ "
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (call.status == MobileToolCall.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        // Title and summary arrive already decided by the plugin, so the two
                        // clients cannot disagree about what a tool call is called.
                        Text(call.title, style = MaterialTheme.typography.bodySmall)
                        if (call.summary.isNotBlank()) {
                            Text(
                                call.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The machine's own sentence about why it refused, and the one gesture that clears it. */
@Composable
private fun NoticeBar(message: String, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss this message",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Past this the pill has stopped being enough, and the expander earns its place. */
private const val EXPAND_AT_CHARS = 120

/**
 * The same draft, with the whole screen.
 *
 * A prompt worth sending to an agent is often a paragraph, and a five-line pill above a
 * keyboard shows two of them. Nothing is copied: the field writes straight through to the
 * conversation's draft, so closing this — or the process dying while it is open — leaves the
 * text exactly where the composer will find it.
 */
@Composable
private fun FullScreenEditor(
    draft: String,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Back to the conversation")
                    }
                    Text(
                        "${draft.length} characters",
                        Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank() && !sending,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send", Modifier.size(20.dp))
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Message this agent") },
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

/**
 * The composer: one pill, one send button.
 *
 * What it replaced was four stacked rows — quick replies, the field, and a permanent
 * "Stop & send"/"Stop" pair — occupying a third of a phone screen at all times. The chips now
 * appear only while there is nothing typed (once there is, they are a way to overwrite it),
 * plain "Stop" moved onto the working bubble where the reader is already looking, and
 * "Stop & send" appears only in the one state where it differs from Send: a live run with
 * something typed.
 */
@Composable
private fun Composer(
    draft: String,
    running: Boolean,
    queued: List<OutgoingSend>,
    delivering: String?,
    onRetryQueued: (String) -> Unit,
    onEditQueued: (String) -> Unit,
    onDiscardQueued: (String) -> Unit,
    fieldFocus: FocusRequester,
    onDraft: (String) -> Unit,
    onSend: (String, Boolean) -> Unit,
    pills: @Composable () -> Unit = {},
    photos: List<PendingPhoto> = emptyList(),
    attaching: Boolean = false,
    canAttach: Boolean = false,
    onAttach: (ByteArray) -> Unit = {},
    onRemovePhoto: (String) -> Unit = {},
    /** Null when the machine does not advertise `files`; the `@` popup then never opens. */
    onSearchFiles: (suspend (String) -> MentionMatches)? = null,
    /** Null when the machine does not advertise `issues`; the `#` popup then never opens. */
    onSearchIssues: (suspend (String) -> MobileIssueList)? = null,
    commandScope: String = "",
    commandVendor: AgentVendor = AgentVendor.CLAUDE,
    /** Null when the machine does not advertise `commands`; the `/` popup then never opens. */
    onLoadCommands: (suspend () -> List<MobileCommand>?)? = null,
    /** Null when the machine does not advertise `prompt-history`; no "Earlier prompts" chip then. */
    onLoadPrompts: (suspend () -> List<String>?)? = null,
    emojiCompletion: Boolean = true,
    stash: ComposerStashActions? = null,
    /** The desk-only commands this screen can answer with its own controls ([MobileDeskCommands]). */
    deskActions: Set<MobileDeskCommands.Action> = emptySet(),
    /** Runs one; false when it declined (nothing to stop, nothing to copy) and the line stays. */
    onDeskCommand: (MobileDeskCommands.Action) -> Boolean = { false },
    /** Opens "Schedule into this chat"; null when the machine cannot queue a prompt for later. */
    onSchedule: (() -> Unit)? = null,
) {
    // Claude reads a command only at the start of the prompt; Codex's are `$skill` mentions, plus
    // the `/compact` the machine runs as Codex's own compaction.
    val commandTriggers = if (commandVendor == AgentVendor.CODEX) setOf('$', '/') else setOf('/')
    val hasDraft = draft.isNotBlank()
    // A photo alone is a sendable message, as it is on the desktop: the attachment *is* the
    // instruction when a reader photographs the thing they are asking about.
    val hasContent = hasDraft || photos.isNotEmpty()
    // Only the send whose bytes are on the wire disables the button. A prompt merely queued or
    // parked must not: it is already out of the composer, and the reader is entitled to type the
    // same sentence again — declining that silently is a refusal.
    val submittingDraft = queued.any { it.prompt == draft && it.clientMessageId == delivering }
    val haptics = LocalHapticFeedback.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    // One read per conversation, on the first trigger: the catalogue is small and the popup
    // filters it locally, so a keystroke never costs a round trip. A failed read is retried
    // the next time the trigger opens, not cached as "no commands". Up here because Send also
    // asks it whether a desk command's name is the user's own.
    var catalogue by remember(commandScope) { mutableStateOf<List<MobileCommand>?>(null) }
    var commandsLoading by remember(commandScope) { mutableStateOf(false) }
    var commandsFailed by remember(commandScope) { mutableStateOf(false) }
    // A bare desk-only command runs its control here, as the desk's composer consumes it; sent,
    // it would reach the agent as prose. A photo makes the line a message again.
    fun submit(stopFirst: Boolean) {
        val action = MobileDeskCommands.actionFor(draft, commandVendor, deskActions, catalogue)
            ?.takeIf { photos.isEmpty() }
        val scheduled = MobileDeskCommands.scheduleText(draft, commandVendor, deskActions, catalogue)
            ?.takeIf { photos.isEmpty() }
        if (scheduled != null) {
            if (onDeskCommand(MobileDeskCommands.Action.SCHEDULE)) onDraft(scheduled)
        } else if (action == null) {
            onSend(draft, stopFirst)
        } else if (onDeskCommand(action)) {
            onDraft("")
        }
    }

    if (expanded) {
        FullScreenEditor(
            draft = draft,
            onDraft = onDraft,
            onDismiss = { expanded = false },
            sending = submittingDraft,
            onSend = {
                expanded = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                submit(stopFirst = false)
            },
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutgoingChip(queued, delivering, onRetryQueued, onEditQueued, onDiscardQueued)
        AnimatedVisibility(visible = !hasDraft, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // First: a prompt the reader parked on purpose outranks one merely typed before.
                stash?.let { StashedPromptsChip(it) }
                onLoadPrompts?.let { load ->
                    EarlierPromptsChip(commandVendor, load, onPick = onDraft)
                }
                QUICK_REPLIES.forEach { (label, reply) ->
                    // Composed into the draft, not fired past it: these used to send instantly
                    // and discard whatever was already typed. Same join rule dictation uses.
                    SuggestionChip(
                        onClick = { onDraft(Dictation.append(draft, reply)) },
                        label = { Text(label) },
                        shape = CircleShape,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = null,
                    )
                }
            }
        }
        // In the quick replies' slot: what the send will run with matters once there is a send.
        AnimatedVisibility(visible = hasDraft, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { pills() }
                // Beside the run line rather than in the field: the field's trailing slot already
                // holds attach and dictate, and a fourth glyph there costs a 360dp phone its text.
                // Text only, as on the desk — so not while photos wait: they would stay behind
                // and go out with whatever is typed next.
                stash?.takeIf { photos.isEmpty() }?.let {
                    // The run line reserves 8dp below itself; the same here keeps both centred.
                    DeckIconButton("Stash this message for later", DeckIcons.Stash, onClick = it.onStash,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                // Text only for the same reason: a scheduled prompt carries no photos.
                onSchedule?.takeIf { photos.isEmpty() }?.let {
                    DeckIconButton("Schedule this message", Icons.Filled.DateRange, onClick = it,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        PhotoChips(photos, onRemovePhoto)
        // Caret-tracking lives here and nowhere else: `drafts` stays a plain string map, so
        // persistence, dictation, the quick replies and a parked item's Edit are untouched by
        // a completion feature only the field itself needs (see `MentionDraft`).
        var field by remember { mutableStateOf(TextFieldValue(draft)) }
        if (field.text != draft) field = TextFieldValue(draft, TextRange(draft.length))
        val active = onSearchFiles?.let { MentionDraft.activeAt(field.text, field.selection.start) }
        var matches by remember { mutableStateOf(MentionMatches()) }
        // Keyed on the *query* and a stable flag, never on the lambda: it is re-created on every
        // recomposition, so keying on it restarted the debounce — and re-issued the request —
        // every time a streaming turn repainted the screen behind the popup.
        val canSearch = onSearchFiles != null
        val search by rememberUpdatedState(onSearchFiles)
        LaunchedEffect(active?.query, canSearch) {
            val query = active?.query
            val onSearchFiles = search
            if (onSearchFiles == null || query == null || query.length < MentionDraft.MIN_QUERY) {
                matches = MentionMatches()
                return@LaunchedEffect
            }
            // One keystroke's grace before a round trip: a thumb typing `@Conver` would
            // otherwise spend six index queries to answer the seventh.
            delay(MENTION_DEBOUNCE_MS)
            matches = onSearchFiles(query)
        }
        if (active != null && active.query.length >= MentionDraft.MIN_QUERY) {
            MentionPopup(matches, onPick = { path ->
                val (text, caret) = MentionDraft.accept(field.text, active, path)
                field = TextFieldValue(text, TextRange(caret))
                onDraft(text)
            })
        }
        val command = onLoadCommands?.let { MobileCommandQuery.activeAt(field.text, field.selection.start) }
            ?.takeIf { it.trigger in commandTriggers }
        val loadCommands by rememberUpdatedState(onLoadCommands)
        LaunchedEffect(command != null, commandScope) {
            if (command == null || catalogue != null) return@LaunchedEffect
            val load = loadCommands ?: return@LaunchedEffect
            commandsLoading = true
            val found = load()
            commandsLoading = false
            commandsFailed = found == null
            catalogue = found
        }
        if (command != null && active == null) {
            // A scheduled prompt carries no photos, so with some staged the popup does not offer it.
            val popupActions = if (photos.isEmpty()) deskActions else deskActions - MobileDeskCommands.Action.SCHEDULE
            val deskRows = catalogue?.let { MobileDeskCommands.offered(commandVendor, popupActions, it) }.orEmpty()
            CommandPopup(
                rows = catalogue?.let { MobileCommandQuery.matching(it + deskRows.map { row -> row.asCommand() }, command) }.orEmpty(),
                loading = commandsLoading,
                unreachable = commandsFailed,
                onPick = { row ->
                    val desk = deskRows.firstOrNull { it.name == row.name }
                    if (desk == null) {
                        val (text, caret) = MobileCommandQuery.accept(field.text, command, row)
                        field = TextFieldValue(text, TextRange(caret))
                        onDraft(text)
                    } else if (onDeskCommand(desk.action)) {
                        // Only the command is consumed; anything typed after it stays.
                        val rest = field.text.drop(command.anchor + 1 + command.query.length).trimStart()
                        field = TextFieldValue(rest, TextRange(0))
                        onDraft(rest)
                    }
                },
            )
        }
        // `#` asks the machine's trackers, debounced as the desk's popup is (250 ms); no rows means
        // no popup, as there — a machine with no tracker configured answers every `#` with none.
        val issue = onSearchIssues?.let { MobileIssueQuery.activeAt(field.text, field.selection.start) }
            ?.takeIf { active == null && command == null }
        var issueRows by remember { mutableStateOf<MobileIssueList?>(null) }
        val searchIssues by rememberUpdatedState(onSearchIssues)
        LaunchedEffect(issue?.query, issue?.anchor) {
            val query = issue?.query
            val onSearch = searchIssues
            if (query == null || onSearch == null) {
                issueRows = null
                return@LaunchedEffect
            }
            delay(ISSUE_DEBOUNCE_MS)
            issueRows = onSearch(query).takeIf { it.query == query.take(MobileIssueQuery.MAX_QUERY) }
        }
        val shownIssues = issueRows?.takeIf { issue != null && (it.issues.isNotEmpty() || it.unavailable) }
        if (issue != null && shownIssues != null) {
            IssuePopup(shownIssues.issues, shownIssues.unavailable, onPick = { row ->
                val (text, caret) = MobileIssueQuery.accept(field.text, issue, row)
                field = TextFieldValue(text, TextRange(caret))
                onDraft(text)
            })
        }
        // Needs no machine: the table is the desk's own, compiled in. Parsed once off Main so
        // the first `:` typed does not pay for 1,500 rows on the frame that draws it.
        LaunchedEffect(emojiCompletion) {
            if (emojiCompletion) withContext(Dispatchers.Default) { EmojiCompletion.shortcodes }
        }
        val emoji = EmojiDraft.activeAt(field.text, field.selection.start)
            ?.takeIf { emojiCompletion && active == null && command == null && shownIssues == null }
        val emojiRows = emoji?.let(EmojiDraft::suggestions).orEmpty()
        if (emoji != null && emojiRows.isNotEmpty()) {
            EmojiPopup(emojiRows, onPick = { row ->
                val (text, caret) = EmojiDraft.accept(field.text, emoji, row.emoji)
                field = TextFieldValue(text, TextRange(caret))
                onDraft(text)
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = field,
                onValueChange = { next ->
                    val swap = if (emojiCompletion) {
                        EmojiDraft.swapAfterTyping(field.text, next.text, next.selection.start)
                    } else null
                    field = swap?.let { (text, caret) -> TextFieldValue(text, TextRange(caret)) } ?: next
                    if (field.text != draft) onDraft(field.text)
                },
                placeholder = { Text("Message this agent") },
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Only once the pill has stopped being big enough. A long prompt is
                        // the one a phone composer is worst at, and a permanent "expand"
                        // button beside an empty field is a control for nothing.
                        if (draft.length > EXPAND_AT_CHARS) {
                            IconButton(onClick = { expanded = true }) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Write this in a full-screen editor",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (canAttach) AttachPhotoButton(busy = attaching, onPicked = onAttach)
                        DictateButton(onSpoken = { onDraft(Dictation.append(draft, it)) })
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            if (running && hasContent) {
                DeckIconButton("Stop & send", DeckIcons.StopAndSend,
                    onClick = { submit(stopFirst = true) }, enabled = !submittingDraft)
            }
            FilledIconButton(
                onClick = {
                    // Acknowledgement is reported by the view model after the bridge answers.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    submit(stopFirst = false)
                },
                enabled = hasContent && !submittingDraft,
                modifier = Modifier.padding(start = 8.dp).size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** One keystroke's grace before the `@` popup spends a round trip on the machine's index. */
private const val MENTION_DEBOUNCE_MS = 180L

/** The desk's `#` debounce: a tracker round trip is dearer than an index query. */
private const val ISSUE_DEBOUNCE_MS = 250L
