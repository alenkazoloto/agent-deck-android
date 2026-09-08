package dev.agentdeck.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Search
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
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.FleetGrouping
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.RowAction
import dev.agentdeck.companion.data.RowActions
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
    onBrowsing: (FleetBrowsing) -> Unit = {},
) {
    val allRows = snapshot?.rows.orEmpty()
    val rows = Snooze.apply(snoozed, allRows)
    val hiddenCount = Snooze.hidden(snoozed, allRows)
    val generatedAtMs = snapshot?.generatedAtMs ?: 0L
    val sections = FleetGrouping.sections(rows, filter, generatedAtMs, sort)
    var expandedGroups by remember(browsingScope) { mutableStateOf(browsing.expandedGroups) }
    val listState = remember(browsingScope, filter, sort) { LazyListState() }
    val initialAnchor = remember(listState) { browsing.anchor }
    var restored by remember(listState) { mutableStateOf(false) }
    val itemKeys = buildList {
        if (hiddenCount > 0) add("snoozed")
        if (snapshot == null) add("loading") else if (sections.isEmpty()) add("empty")
        sections.forEach { section ->
            val group = section.group?.name ?: "all"
            val view = FleetGrouping.view(section, sections.size, group in expandedGroups)
            add("head-$group")
            addAll(view.rows.map { it.key })
            if (view.hidden > 0) add("more-$group")
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
    var sheetRow by remember { mutableStateOf<MobileFleetRow?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Outside the scroller. As item #1 of the LazyColumn, search and every selector left
        // the screen on the first flick — the same harm as a "Filters (N)" button, reached by
        // scrolling rather than by clicking.
        FleetFilters(rows, filter, sort, onFilter, onSort)

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
                } else if (sections.isEmpty()) {
                    item(key = "empty") {
                        FleetEmpty(
                            // A grouped list drops only what the filter excluded, so rows with
                            // no sections is always the filter's doing.
                            filtered = !filter.isEmpty,
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
                        SectionHeader(if (section.group == null) "${filter.scope.label} chats" else section.title, view.total, section.group)
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
                        )
                    }
                    if (view.hidden > 0) {
                        item(key = "more-$groupKey") {
                            ShowAllRow(view.total) { expandedGroups = expandedGroups + groupKey }
                        }
                    }
                }
                snapshot?.usageLine?.takeIf { it.isNotBlank() }?.let { usage ->
                    item(key = "usage") { UsageBand(usage) }
                }
            }
        }
    }

    sheetRow?.let { row ->
        RowSheet(
            row = row,
            group = FleetGrouping.groupOf(row, generatedAtMs),
            generatedAtMs = generatedAtMs,
            onDismiss = { sheetRow = null },
            onOpen = { sheetRow = null; onOpen(row) },
            onSnooze = { sheetRow = null; onSnooze(row) },
            onStop = { sheetRow = null; onStop(row) },
        )
    }
}

/**
 * Swipe one way to snooze, the other to stop — the two acts a thumb over a list wants, and
 * neither of them needed the conversation opened first.
 *
 * **Only the reversible one is destructive-looking.** A snooze hides a row until its agent
 * moves and is undoable from the snackbar it raises; a stop is dispatched immediately and
 * *not* offered an undo, because "undo" would have to mean a five-second delay before
 * interrupting an agent that is spending tokens, and a button that promises to un-stop a run
 * it already stopped would be a lie. Stopping is recoverable by sending again, which is why it
 * is the swipe that does not need a grace period.
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
) {
    val haptics = LocalHapticFeedback.current
    val running = group == FleetGroup.RUNNING
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
                SwipeToDismissBoxValue.EndToStart -> {
                    if (!running) return@rememberSwipeToDismissBoxState false
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStop(row)
                    false
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = running,
        backgroundContent = { SwipeBackdrop(state.dismissDirection, running) },
    ) {
        FleetRow(row, group, generatedAtMs, selected, onOpen, onDetails, onSnooze, onStop)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackdrop(direction: SwipeToDismissBoxValue, running: Boolean) {
    val settled = direction == SwipeToDismissBoxValue.Settled
    val snoozing = direction == SwipeToDismissBoxValue.StartToEnd
    val label = if (settled) "" else if (snoozing) "Snooze" else if (running) "Stop" else ""
    val color = when {
        settled -> Color.Transparent
        snoozing -> MaterialTheme.colorScheme.secondaryContainer
        running -> MaterialTheme.colorScheme.errorContainer
        else -> Color.Transparent
    }
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
private fun FleetEmpty(filtered: Boolean, onClear: () -> Unit, onRefresh: () -> Unit) {
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
            if (filtered) "Nothing matches" else "Your work, within reach",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
                "Searches chat titles, projects, branches and current activity",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FleetScope.entries.forEach { scope ->
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
) {
    val haptics = LocalHapticFeedback.current
    // The same list the long-press sheet draws, so a gesture and a screen reader can never
    // offer different things. Open is the row's own click, and Copy needs the clipboard the
    // sheet holds — both are reached through Show details rather than duplicated here.
    val actions = RowActions.of(group).mapNotNull { action ->
        when (action) {
            RowAction.SNOOZE -> CustomAccessibilityAction(action.label) { onSnooze(row); true }
            RowAction.STOP -> CustomAccessibilityAction(action.label) { onStop(row); true }
            RowAction.OPEN, RowAction.COPY_TITLE -> null
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
                contentDescription = rowAnnouncement(row, group)
                customActions = actions
            },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(16.dp)) {
            RowAvatar(row.vendor, group)
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
                // One live line, already decided by the plugin: the running tool ticker, the
                // failure reason, or the waiting reason. The phone does not re-derive it.
                row.liveLine?.takeIf { it.isNotBlank() }?.let { live ->
                    Text(
                        live,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (group == FleetGroup.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }

            }
        }
    }
}

/** The row as one sentence. Ordered by what decides whether to open it. */
internal fun rowAnnouncement(row: MobileFleetRow, group: FleetGroup): String = buildString {
    append(row.vendor.label()).append(" conversation, ")
    append(row.title.ifBlank { "no title" }).append(", ")
    append(group.title)
    row.liveLine?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
    if (row.projectName.isNotBlank()) append(", in ").append(row.projectName)
}

/**
 * The agent's glyph in a tinted disc, with its attention state as a badge on the corner.
 *
 * The badge is [StateMark] unchanged — the shape-plus-tint pair `FleetTriageTest` pins.
 *
 * **Its plate may not be a surface role the card also uses.** It was `surfaceContainerLow`,
 * which is exactly what an unselected row card is painted in: the plate was invisible against
 * the card, so the only edge it drew was the one where it erased the avatar, and the badge
 * read as a bite out of the disc. The ring appeared on selected rows alone, whose card is
 * `secondaryContainer`. The stroke is what carries it now — `outline` is the role specified to
 * hold contrast against *any* surface — so no card colour can swallow the badge again.
 *
 * Neither mark carries its own description: the whole row announces once, and a vendor label
 * repeated inside a merged node is read twice.
 */
@Composable
private fun RowAvatar(vendor: AgentVendor, group: FleetGroup) {
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                vendor.glyph(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(18.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Why the row is where it is, on the row — the section heading that used to be the
            // only carrier of this is off screen for most of a long list.
            StateMark(group)
        }
    }
}

@Composable
private fun <T> rememberBrowsingValue(scope: Any, value: T): State<T> =
    remember(scope) { mutableStateOf(value) }.also { it.value = value }
