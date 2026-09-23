package dev.agentdeck.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.FleetBrowsing
import dev.agentdeck.companion.data.ReadingAnchor
import kotlinx.coroutines.flow.first
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFolder
import com.github.claudeagents.core.mobile.MobileFolderActionRequest
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import com.github.claudeagents.core.mobile.MobileSessionDeletePreview
import com.github.claudeagents.core.mobile.MobileForkPoint
import com.github.claudeagents.core.mobile.MobileSessionForkPoints
import com.github.claudeagents.core.mobile.MobileSessionSearchRequest
import dev.agentdeck.companion.data.MessageSearch
import kotlinx.coroutines.delay
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.FleetGrouping
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.stateLabel
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.RowAction
import dev.agentdeck.companion.data.RowActions
import dev.agentdeck.companion.data.FleetPins
import dev.agentdeck.companion.data.PinOrder
import dev.agentdeck.companion.data.Snooze

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FleetScreen(
    snapshot: MobileFleetSnapshot?,
    filter: FleetFilter,
    sort: FleetSort,
    refreshing: Boolean,
    snoozed: Map<String, Long>,
    openKey: String?,
    onFilter: (FleetFilter) -> Unit,
    onSort: (FleetSort) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (MobileFleetRow) -> Unit,
    onSnooze: (MobileFleetRow) -> Unit,
    onStop: (MobileFleetRow) -> Unit,
    browsingScope: String = "",
    browsing: FleetBrowsing = FleetBrowsing(),
    /** Holds the recency ordering still while a thumb is over the list — see [FleetPins]. */
    pins: FleetPins = FleetPins.NONE,
    onBrowsing: (FleetBrowsing) -> Unit = {},
    /** The machine serves `/v1/review`, so a Done-unreviewed row can be cleared from here. */
    canReview: Boolean = false,
    onMarkReviewed: (MobileFleetRow) -> Unit = {},
    /** The machine serves `/v1/session-actions`: pin, Done, reopen and rename. */
    canOrganize: Boolean = false,
    onSessionAction: (MobileFleetRow, String, String?) -> Unit = { _, _, _ -> },
    /** The machine serves `/v1/session-export`: a chat can leave through the share sheet. */
    canShare: Boolean = false,
    onShare: (MobileFleetRow) -> Unit = {},
    /** The machine serves `/v1/session-delete`: the sheet asks for a preview, [deletePreview] confirms it. */
    canDelete: Boolean = false,
    onDelete: (MobileFleetRow) -> Unit = {},
    deletePreview: MobileSessionDeletePreview? = null,
    onConfirmDelete: (MobileSessionDeletePreview) -> Unit = {},
    onDismissDelete: () -> Unit = {},
    /** The machine serves `/v1/session-search`: the search also looks through messages. */
    canSearchMessages: Boolean = false,
    messageSearch: MessageSearch? = null,
    onSearchMessages: (String, List<String>) -> Unit = { _, _ -> },
    onSearchMoreMessages: () -> Unit = {},
    /** The machine serves `session-folders`: a row files into the desk's Sessions folders. */
    canFolder: Boolean = false,
    /** Files a row: a folder id (blank for none), or a new folder's name. */
    onFile: (row: MobileFleetRow, folderId: String?, newFolder: String?) -> Unit = { _, _, _ -> },
    /** The machine serves `/v1/folder-actions`: the folder the list is filtered to can be edited or deleted. */
    canEditFolders: Boolean = false,
    onFolderAction: (MobileFolderActionRequest) -> Unit = {},
    /** The machine serves `/v1/session-fork`: [forkPoints] is the picker [onFork] opened for a Claude chat. */
    canFork: Boolean = false,
    onFork: (MobileFleetRow) -> Unit = {},
    forkPoints: MobileSessionForkPoints? = null,
    onForkAt: (MobileSessionForkPoints, MobileForkPoint) -> Unit = { _, _ -> },
    onDismissFork: () -> Unit = {},
    /** The machine serves a whole Claude copy (`session-branch`); [onBranch] is either vendor's Branch chat. */
    canBranch: Boolean = false,
    onBranch: (MobileFleetRow) -> Unit = {},
    /** The machine serves the `pin-order` verb: a pin moves among [painted], the pins its section paints. */
    canPinOrder: Boolean = false,
    onMovePin: (row: MobileFleetRow, painted: List<String>, delta: Int) -> Unit = { _, _, _ -> },
    /** The machine serves `/v1/session-rewind`: [onRewind] opens the desk's `/rewind` picker. */
    canRewind: Boolean = false,
    onRewind: (MobileFleetRow) -> Unit = {},
    /** The machine serves `/v1/commit-staged`: the desk's "Commit staged changes…" for the row's checkout. */
    canCommitStaged: Boolean = false,
    onCommitStaged: (MobileFleetRow) -> Unit = {},
    /** The machine serves the `retitle` verb: the desk's "Regenerate title". */
    canRetitle: Boolean = false,
    onRetitle: (MobileFleetRow) -> Unit = {},
) {
    val allRows = snapshot?.rows.orEmpty()
    val rows = Snooze.apply(snoozed, allRows)
    val hiddenCount = Snooze.hidden(snoozed, allRows)
    val generatedAtMs = snapshot?.generatedAtMs ?: 0L
    val folders = snapshot?.folders.orEmpty()
    val shown = filter.withFoldersOf(folders)
    val sections = FleetGrouping.sections(rows, shown, generatedAtMs, sort, pins)
    val query = shown.query.trim()
    val messageKeys = if (canSearchMessages && MobileSessionSearchRequest.eligible(query)) {
        MessageSearch.population(rows, shown)
    } else {
        emptyList()
    }
    // After a pause in typing, so a word typed letter by letter asks once, not once per letter.
    LaunchedEffect(query, messageKeys) {
        if (messageKeys.isNotEmpty()) delay(MESSAGE_SEARCH_PAUSE_MS)
        onSearchMessages(query, messageKeys)
    }
    // During the typing pause nothing has been asked yet, and that reads as searching: an
    // empty list there would say "No chats match" about messages nobody has read.
    val search = messageSearch?.takeIf { messageKeys.isNotEmpty() && it.query == query }
        ?: MessageSearch(query, messageKeys, searching = true).takeIf { messageKeys.isNotEmpty() }
    val rowsByKey = rows.associateBy { it.key }
    val messageHits = search?.visibleHits(messageKeys).orEmpty().mapNotNull { hit -> rowsByKey[hit.key]?.let { it to hit.snippet } }
    // "No chats match" is said only once the messages were searched too, and found nothing.
    val showEmpty = sections.isEmpty() && (search == null || (search.complete && !search.timedOut && messageHits.isEmpty()))
    var expandedGroups by remember(browsingScope) { mutableStateOf(browsing.expandedGroups) }
    val listState = remember(browsingScope, filter, sort) { LazyListState() }
    val initialAnchor = remember(listState) { browsing.anchor }
    var restored by remember(listState) { mutableStateOf(false) }
    val itemKeys = buildList {
        if (hiddenCount > 0) add("snoozed")
        if (snapshot == null) add("loading") else if (showEmpty) add("empty")
        sections.forEach { section ->
            val group = section.group?.name ?: "all"
            val view = FleetGrouping.view(section, sections.size, group in expandedGroups)
            add("head-$group")
            addAll(view.rows.map { it.key })
            if (view.hidden > 0) add("more-$group")
        }
        if (search != null && !showEmpty) {
            if (messageHits.isNotEmpty()) add("messages-head")
            addAll(messageHits.map { "message:" + it.first.key })
            add("messages-status")
        }
        if (!snapshot?.usageLine.isNullOrBlank()) add("usage")
    }
    val currentKeys by rememberBrowsingValue(listState, itemKeys)
    val currentExpanded by rememberBrowsingValue(listState, expandedGroups)
    val currentCallback by rememberBrowsingValue(listState, onBrowsing)
    val persist by rememberBrowsingValue(listState, {
        if (restored && currentKeys.isNotEmpty()) {
            val index = listState.firstVisibleItemIndex.coerceIn(0, currentKeys.lastIndex)
            currentCallback(FleetBrowsing(
                ReadingAnchor(currentKeys[index], index, listState.firstVisibleItemScrollOffset, false),
                currentExpanded, filter, sort,
            ))
        }
    })
    LaunchedEffect(listState, snapshot != null) {
        if (snapshot != null && !restored) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            listState.scrollToItem(initialAnchor.resolvedIndex(currentKeys), initialAnchor.offset)
            restored = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listOf(restored, listState.isScrollInProgress,
            listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, expandedGroups) }
            .collect { if (!listState.isScrollInProgress) persist() }
    }
    DisposableEffect(listState) { onDispose { persist() } }
    // Read here, from the layout before this composition's items: message hits arrive above the
    // "Searching…" line, and the list keeps that line on top, under the sticky "In messages"
    // header, so a reader at the top saw the header and no hit (J08). A reader who scrolled stays put.
    val atTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    val topBeforeHits = atTop
    val hitKeys = messageHits.map { it.first.key }
    LaunchedEffect(listState, hitKeys) {
        if (restored && topBeforeHits && hitKeys.isNotEmpty()) listState.scrollToItem(0)
    }
    var sheetRow by remember { mutableStateOf<MobileFleetRow?>(null) }
    var renameRow by remember { mutableStateOf<MobileFleetRow?>(null) }
    var folderRow by remember { mutableStateOf<MobileFleetRow?>(null) }
    var editFolderId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Outside the scroller. As item #1 of the LazyColumn, search and every selector left
        // the screen on the first flick — the same harm as a "Filters (N)" button, reached by
        // scrolling rather than by clicking.
        FleetFilters(
            rows, shown, sort, onFilter, onSort, searchesMessages = canSearchMessages, folders = folders,
            onEditFolder = if (canEditFolders) { folder -> editFolderId = folder.id } else null,
        )

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // The New-chat FAB floats over this list's bottom-right corner, and the last
                // row of a fleet is the oldest, quietest one — so without this the thing the
                // button covers is a conversation nobody would notice was covered.
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                // Snoozed chats stay counted until new agent activity makes them visible.
                if (hiddenCount > 0) {
                    item(key = "snoozed") { SnoozedRow(hiddenCount) }
                }

                // No snapshot is not an empty fleet: it is the state before the first frame,
                // and this screen used to say "no conversations" over a link still connecting.
                if (snapshot == null) {
                    item(key = "loading") { FleetSkeleton() }
                } else if (showEmpty) {
                    item(key = "empty") {
                        FleetEmpty(
                            // A grouped list drops only what the filter excluded, so rows with
                            // no sections is always the filter's doing.
                            filtered = !shown.isEmpty,
                            query = shown.query.trim(),
                            onClear = { onFilter(FleetFilter()) },
                            onRefresh = onRefresh,
                        )
                    }
                }

                sections.forEach { section ->
                    val groupKey = section.group?.name ?: "all"
                    val view = FleetGrouping.view(section, sections.size, groupKey in expandedGroups)

                    // Sticky: fifty rows into a backlog, "which group am I in?" had no answer
                    // on screen at all.
                    stickyHeader(key = "head-$groupKey") {
                        SectionHeader(if (section.group == null) "${shown.scope.label} chats" else section.title, view.total, section.group)
                    }
                    items(view.rows, key = { it.key }) { row ->
                        SwipeableRow(
                            row = row,
                            group = FleetGrouping.groupOf(row, generatedAtMs),
                            generatedAtMs = generatedAtMs,
                            selected = row.key == openKey,
                            onOpen = onOpen,
                            onSnooze = onSnooze,
                            onStop = onStop,
                            onDetails = { sheetRow = row },
                            canOrganize = canOrganize,
                            onSessionAction = onSessionAction,
                        )
                    }
                    if (view.hidden > 0) {
                        item(key = "more-$groupKey") {
                            ShowAllRow(view.total) { expandedGroups = expandedGroups + groupKey }
                        }
                    }
                }
                if (search != null && !showEmpty) {
                    if (messageHits.isNotEmpty()) {
                        stickyHeader(key = "messages-head") { SectionHeader("In messages", messageHits.size, null) }
                    }
                    // Keyed apart from the title rows: a chat can move between the two lists.
                    items(messageHits, key = { "message:" + it.first.key }) { (row, snippet) ->
                        SwipeableRow(
                            row = row,
                            group = FleetGrouping.groupOf(row, generatedAtMs),
                            generatedAtMs = generatedAtMs,
                            selected = row.key == openKey,
                            onOpen = onOpen,
                            onSnooze = onSnooze,
                            onStop = onStop,
                            onDetails = { sheetRow = row },
                            canOrganize = canOrganize,
                            onSessionAction = onSessionAction,
                            snippet = snippet,
                        )
                    }
                    item(key = "messages-status") { MessageSearchStatus(search, messageHits.size, onSearchMoreMessages) }
                }
                snapshot?.usageLine?.takeIf { it.isNotBlank() }?.let { usage ->
                    item(key = "usage") { UsageBand(usage) }
                }
            }
        }
    }

    sheetRow?.let { row ->
        // The rows the section shows, not the ones behind "Show all": a move never swaps with a hidden pin.
        val paintedPins = PinOrder.painted(
            sections.firstOrNull { s -> s.rows.any { it.key == row.key } }
                ?.let { s -> FleetGrouping.view(s, sections.size, (s.group?.name ?: "all") in expandedGroups).rows }
                .orEmpty(),
        )
        RowSheet(
            row = row,
            group = FleetGrouping.groupOf(row, generatedAtMs),
            generatedAtMs = generatedAtMs,
            onDismiss = { sheetRow = null },
            onOpen = { sheetRow = null; onOpen(row) },
            onSnooze = { sheetRow = null; onSnooze(row) },
            onStop = { sheetRow = null; onStop(row) },
            canReview = canReview,
            onMarkReviewed = { onMarkReviewed(row) },
            canOrganize = canOrganize,
            onSessionAction = { action, title -> onSessionAction(row, action, title) },
            onRename = { renameRow = row },
            canShare = canShare,
            onShare = { onShare(row) },
            canDelete = canDelete,
            onDelete = { onDelete(row) },
            canFolder = canFolder,
            folderName = folders.firstOrNull { it.id == row.folderId }?.name,
            onFolder = { folderRow = row },
            canFork = canFork,
            onFork = { onFork(row) },
            canBranch = canBranch,
            onBranch = { onBranch(row) },
            canPinOrder = canPinOrder,
            paintedPins = paintedPins.size,
            onMovePin = { delta -> onMovePin(row, paintedPins, delta) },
            canRetitle = canRetitle,
            onRetitle = { onRetitle(row) },
            canRewind = canRewind,
            onRewind = { onRewind(row) },
            canCommitStaged = canCommitStaged,
            onCommitStaged = { onCommitStaged(row) },
        )
    }
    forkPoints?.let { points ->
        ForkDialog(points, onDismiss = onDismissFork, onPick = { onForkAt(points, it) })
    }
    // By id, so a frame that deletes the folder at the desk closes the form.
    folders.firstOrNull { it.id == editFolderId }?.let { folder ->
        FolderEditDialog(
            folder = folder,
            folders = folders,
            onDismiss = { editFolderId = null },
            onSave = { request -> editFolderId = null; onFolderAction(request) },
        )
    }
    folderRow?.let { row ->
        FolderDialog(
            currentId = row.folderId?.takeIf { id -> folders.any { it.id == id } },
            folders = folders,
            onDismiss = { folderRow = null },
            onPick = { id -> folderRow = null; onFile(row, id, null) },
            onCreate = { name -> folderRow = null; onFile(row, null, name) },
        )
    }
    deletePreview?.let { preview ->
        DeleteDialog(preview, onDismiss = onDismissDelete, onDelete = { onConfirmDelete(preview) })
    }
    renameRow?.let { row ->
        RenameDialog(
            current = row.title,
            codex = row.vendor == AgentVendor.CODEX,
            onDismiss = { renameRow = null },
            onRename = { name ->
                renameRow = null
                onSessionAction(row, MobileSessionActionRequest.RENAME, name)
            },
        )
    }
}

/**
 * Swipe right to snooze without opening the conversation. Swipe left does nothing: a stop is
 * dispatched immediately with no undo, and a thumb scrolling a list brushes that direction too
 * often for it to interrupt a run that is spending tokens. Stop lives in the long-press sheet
 * and TalkBack's custom actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableRow(
    row: MobileFleetRow,
    group: FleetGroup,
    generatedAtMs: Long,
    selected: Boolean,
    onOpen: (MobileFleetRow) -> Unit,
    onSnooze: (MobileFleetRow) -> Unit,
    onStop: (MobileFleetRow) -> Unit,
    onDetails: () -> Unit,
    canOrganize: Boolean = false,
    onSessionAction: (MobileFleetRow, String, String?) -> Unit = { _, _, _ -> },
    snippet: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSnooze(row)
                    // The row leaves because the snooze filtered it out, not because the box
                    // dismissed it — letting the box settle would strand a dismissed slot
                    // behind a row the undo is about to put back.
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> false
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = { SwipeBackdrop(state.dismissDirection) },
    ) {
        FleetRow(row, group, generatedAtMs, selected, onOpen, onDetails, onSnooze, onStop, canOrganize, onSessionAction, snippet)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackdrop(direction: SwipeToDismissBoxValue) {
    val snoozing = direction == SwipeToDismissBoxValue.StartToEnd
    val label = if (snoozing) "Snooze" else ""
    val color = if (snoozing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .padding(horizontal = 22.dp),
        contentAlignment = if (snoozing) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A group's heading, as the platform's sticky list subheader.
 *
 * It was a filled pill on an opaque band — two containers stacked over a list of cards, and
 * heavier than the rows it labelled. A subheader carries its weight in the type and ends in a
 * rule instead: the band keeps what scrolls under it legible, and the rule is what says the
 * heading is not itself a row.
 */
@Composable
private fun SectionHeader(title: String, total: Int, group: FleetGroup?) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The mark's own description *is* this title, and a merged node would read it twice.
            group?.let { StateMark(it, Modifier.clearAndSetSemantics {}) }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                total.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The expander for a capped group. Its label carries the number, so nothing is hidden silently. */
@Composable
private fun ShowAllRow(total: Int, onExpand: () -> Unit) {
    Text(
        "Show all $total",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = "Show all $total chats in this group",
                onClick = onExpand,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** What the swipes are holding back, and the count they are holding. Never silent. */
@Composable
private fun SnoozedRow(hidden: Int) {
    Text(
        if (hidden == 1) "1 conversation snoozed until its agent moves"
        else "$hidden conversations snoozed until their agents move",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Plan usage — the one number on this screen that is about the account rather than a row.
 *
 * The plugin writes the sentence and the phone never re-derives a percentage out of it, so
 * the treatment is all this can add: a name, since the prose carries none, and the same tonal
 * card the rows under it are drawn in. It scrolls with the list rather than joining the chrome
 * above it — it is worth reading, not worth 40 dp of every screenful.
 */
@Composable
private fun UsageBand(usage: String) {
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Plan usage. $usage" },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "Plan usage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                usage,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The fleet before its first frame, in the shape the rows will take.
 *
 * **Static on purpose.** A shimmer is an `infiniteRepeatable`, and a composition that never
 * goes idle hangs `captureRoboImage` instead of failing it — so an animated skeleton would
 * cost this state the only evidence that it renders at all. The widths are fixed rather than
 * random for the same reason.
 */
@Composable
private fun FleetSkeleton() {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Loading chats" },
    ) {
        SKELETON_WIDTHS.forEach { (project, title) ->
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(Modifier.padding(14.dp)) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        SkeletonBar(project, 10.dp)
                        SkeletonBar(title, 14.dp, top = 8.dp)
                        SkeletonBar(0.5f, 10.dp, top = 10.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(width: Float, height: Dp, top: Dp = 0.dp) {
    Box(
        Modifier
            .padding(top = top)
            .fillMaxWidth(width)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)),
    )
}

/** Project line, then title, per skeleton row. Four rows is about one screenful. */
private val SKELETON_WIDTHS = listOf(0.45f to 0.9f, 0.3f to 0.7f, 0.5f to 0.85f, 0.35f to 0.6f)

/**
 * An empty list as a page rather than a sentence: what is missing, and the one control that
 * changes it.
 *
 * The two emptinesses are different questions and never share a sentence — a machine with
 * nothing on it cannot be fixed by clearing a filter, and a filter that matched nothing says
 * nothing about the machine. Neither offers "New chat": the FAB over this list is already the
 * visible route to it, and a second permanent one is what `CLAUDE.md` refuses.
 */
@Composable
private fun FleetEmpty(filtered: Boolean, query: String, onClear: () -> Unit, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (filtered) Icons.Filled.Search else Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            // The query is echoed so a typo reads as one, not as a machine with no such chat.
            when {
                query.isNotEmpty() -> "No chats match \u201C$query\u201D"
                filtered -> "Nothing matches"
                else -> "Your work, within reach"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            if (filtered) "Try another search or clear the filters to see your other chats."
            // Named, not drawn: the FAB reads as "+" to the eye and announces "New chat", and
            // this sentence has to be true in both.
            else "Start a chat to ask a question, review changes or give your agent a task.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        FilledTonalButton(
            onClick = if (filtered) onClear else onRefresh,
            // Material's button floor is 40 dp — the same 8 dp under the platform's touch
            // minimum that `Selector` raises rather than pads.
            modifier = Modifier.padding(top = 18.dp).heightIn(min = 48.dp),
        ) {
            Text(if (filtered) "Clear filters" else "Check again")
        }
    }
}

/** Search is over the current snapshot; transcript history remains in each chat. */
@Composable
private fun FleetFilters(
    rows: List<MobileFleetRow>,
    filter: FleetFilter,
    sort: FleetSort,
    onFilter: (FleetFilter) -> Unit,
    onSort: (FleetSort) -> Unit,
    searchesMessages: Boolean = false,
    folders: List<MobileFolder> = emptyList(),
    /** Opens the form for the folder the list is filtered to; null when the machine cannot edit folders. */
    onEditFolder: ((MobileFolder) -> Unit)? = null,
) {
    val projects = FleetGrouping.projects(rows)
    val accounts = FleetGrouping.accounts(rows)
    val vendors = FleetGrouping.vendors(rows)
    val models = FleetGrouping.models(rows)

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onFilter(filter.copy(query = it)) },
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(onClick = { onFilter(filter.copy(query = "")) }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (filter.query.isNotBlank()) {
            Text(
                if (searchesMessages) "Searches chat titles, projects, branches, current activity and messages"
                else "Searches chat titles, projects, branches and current activity",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "Done" appears once something is Done, as the desk offers its Done shelf only then.
            FleetScope.entries.filter { it != FleetScope.DONE || filter.scope == it || rows.any { r -> r.done } }.forEach { scope ->
                val selected = scope == filter.scope
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onFilter(filter.copy(scope = scope)) },
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        scope.label,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (projects.size > 1 || filter.projectPath != null) {
                Selector(
                    options = listOf(SelectorOption<String?>("All projects", null)) +
                        projects.map { SelectorOption<String?>(it.projectName, it.projectPath) },
                    selected = filter.projectPath,
                    onSelect = { onFilter(filter.copy(projectPath = it)) },
                )
            }
            // The desk's Sessions folders; absent until the machine has one, as the desk's tree is flat then.
            if (folders.isNotEmpty() || filter.folderId != null) {
                Selector(
                    options = listOf(SelectorOption<String?>("All folders", null), SelectorOption<String?>("No folder", FleetFilter.UNFILED)) +
                        folders.map { SelectorOption<String?>(folderLabel(it), it.id) },
                    selected = filter.folderId,
                    onSelect = { onFilter(filter.copy(folderId = it)) },
                )
                // Only beside one chosen folder: the row that names the folder is where the phone
                // knows which folder is meant, as the desk's folder menu opens on its tree row.
                val chosen = folders.firstOrNull { it.id == filter.folderId }
                if (chosen != null && onEditFolder != null) {
                    DeckIconButton("Edit folder \u201C${chosen.name}\u201D", Icons.Filled.Edit, onClick = { onEditFolder(chosen) })
                }
            }
            if (vendors.size > 1 || filter.vendor != null) {
                Selector(
                    options = listOf(SelectorOption<AgentVendor?>("All agents", null)) +
                        vendors.map { SelectorOption<AgentVendor?>(it.label(), it) },
                    selected = filter.vendor,
                    onSelect = { onFilter(filter.copy(vendor = it)) },
                )
            }
            if (accounts.size > 1 || filter.accountId != null) {
                Selector(
                    options = listOf(SelectorOption<String?>("All accounts", null)) +
                        accounts.map { SelectorOption<String?>(FleetGrouping.accountLabel(rows, it), it) },
                    selected = filter.accountId,
                    onSelect = { onFilter(filter.copy(accountId = it)) },
                )
            }
            if (models.size > 1 || filter.model != null) {
                Selector(
                    options = listOf(SelectorOption<String?>("All models", null)) +
                        models.map { SelectorOption<String?>(it, it) },
                    selected = filter.model,
                    onSelect = { onFilter(filter.copy(model = it)) },
                )
            }
            Selector(
                options = FleetSort.entries.map { SelectorOption(it.label, it) },
                selected = sort,
                onSelect = onSort,
                prefix = "Sort:",
            )
        }
    }
}

/** A chat preview; operational totals remain available from the row's details sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FleetRow(
    row: MobileFleetRow,
    group: FleetGroup,
    generatedAtMs: Long,
    selected: Boolean,
    onOpen: (MobileFleetRow) -> Unit,
    onDetails: () -> Unit,
    onSnooze: (MobileFleetRow) -> Unit,
    onStop: (MobileFleetRow) -> Unit,
    canOrganize: Boolean = false,
    onSessionAction: (MobileFleetRow, String, String?) -> Unit = { _, _, _ -> },
    /** The words around a message hit, for a row found by the message search. */
    snippet: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    // The same list the long-press sheet draws, so a gesture and a screen reader can never
    // offer different things. Open is the row's own click, and Copy needs the clipboard the
    // sheet holds — both are reached through Show details rather than duplicated here.
    val actions = RowActions.of(group, row = row, canOrganize = canOrganize).mapNotNull { action ->
        when (action) {
            RowAction.SNOOZE -> CustomAccessibilityAction(action.label) { onSnooze(row); true }
            RowAction.STOP -> CustomAccessibilityAction(action.label) { onStop(row); true }
            RowAction.PIN, RowAction.UNPIN, RowAction.DONE, RowAction.REOPEN ->
                CustomAccessibilityAction(action.label) { onSessionAction(row, requestFor(action), null); true }
            // Rename and Move to folder need the sheet's dialogs, reached through Show details like Copy, Share, Fork, Rewind, Commit and Delete.
            RowAction.OPEN, RowAction.COPY_TITLE, RowAction.MARK_REVIEWED, RowAction.RENAME, RowAction.FOLDER,
            RowAction.SHARE, RowAction.FORK, RowAction.BRANCH, RowAction.REWIND, RowAction.COMMIT_STAGED, RowAction.DELETE,
            RowAction.MOVE_PIN_UP, RowAction.MOVE_PIN_DOWN, RowAction.RETITLE -> null
        }
    } + CustomAccessibilityAction("Show details") { onDetails(); true }
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = { onOpen(row) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDetails()
                },
                onClickLabel = "Open this conversation",
                onLongClickLabel = "Show what can be done with it",
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = rowAnnouncement(row, group) + snippet?.let { ", message: $it" }.orEmpty()
                customActions = actions
            },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(16.dp)) {
            AgentIdentity(row.vendor, Modifier.padding(top = 2.dp), announce = false)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(row.projectName)
                            row.gitBranch?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.pinned) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp).size(14.dp),
                        )
                    }
                    // Against the snapshot's own stamp, never the phone's clock — the same
                    // rule FleetGrouping.groupOf follows one line above, and without it a row
                    // could read "3h" under the "Recent" heading it had just been sorted into.
                    Text(
                        Times.relative(row.lastActivityMs, generatedAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    row.title.ifBlank { "Untitled chat" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                snippet?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                val live = row.liveLine?.takeIf { it.isNotBlank() }
                val activity = when {
                    // The reason outranks the live line: it says which decision the row needs.
                    group == FleetGroup.WAITING && row.waitingReason != null ->
                        listOfNotNull(stateLabel(row, group), live).joinToString(" · ")
                    live != null -> live
                    group == FleetGroup.RUNNING || group == FleetGroup.WAITING || group == FleetGroup.FAILED -> group.title
                    else -> null
                }
                activity?.let { live ->
                    Row(
                        Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (group == FleetGroup.RUNNING) RunningIndicator()
                        Text(
                            live,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (group == FleetGroup.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

            }
        }
    }
}

/** The row as one sentence. Ordered by what decides whether to open it. */
internal fun rowAnnouncement(row: MobileFleetRow, group: FleetGroup): String = buildString {
    append(row.vendor.label()).append(" conversation, ")
    append(row.title.ifBlank { "no title" }).append(", ")
    append(stateLabel(row, group))
    if (row.pinned) append(", pinned")
    if (row.done) append(", marked done")
    row.liveLine?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
    if (row.projectName.isNotBlank()) append(", in ").append(row.projectName)
}

@Composable
private fun <T> rememberBrowsingValue(scope: Any, value: T): State<T> =
    remember(scope) { mutableStateOf(value) }.also { it.value = value }

/** How long typing must pause before the machine is asked to read messages. */
private const val MESSAGE_SEARCH_PAUSE_MS = 400L
