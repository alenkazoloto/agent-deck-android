package dev.agentdeck.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.FleetGrouping
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
) {
    val allRows = snapshot?.rows.orEmpty()
    val rows = Snooze.apply(snoozed, allRows)
    val hiddenCount = Snooze.hidden(snoozed, allRows)
    val generatedAtMs = snapshot?.generatedAtMs ?: 0L
    val sections = FleetGrouping.sections(rows, filter, generatedAtMs, sort)
    // Which groups the user has opened past their cap. Kept for the app's lifetime, not the
    // snapshot's: a refresh arriving while you read a backlog must not re-collapse it.
    val expandedGroups = remember { mutableStateListOf<String>() }
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
                // The New-chat FAB floats over this list's bottom-right corner, and the last
                // row of a fleet is the oldest, quietest one — so without this the thing the
                // button covers is a conversation nobody would notice was covered.
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                snapshot?.usageLine?.takeIf { it.isNotBlank() }?.let { usage ->
                    item(key = "usage") { UsageBand(usage) }
                }

                // Nothing this app hides is hidden silently. The count is here, and tapping
                // it brings every snoozed row straight back.
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
                        SectionHeader(section.title, view.total, section.group)
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
                            ShowAllRow(view.total) { expandedGroups.add(groupKey) }
                        }
                    }
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
    val snoozing = direction == SwipeToDismissBoxValue.StartToEnd
    val label = if (snoozing) "Snooze" else if (running) "Stop" else ""
    val color = when {
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
                onClickLabel = "Show all $total conversations in this group",
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
            .semantics(mergeDescendants = true) { contentDescription = "Loading conversations" },
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
            if (filtered) "Nothing matches" else "No conversations yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            if (filtered) "No conversation on this machine matches the search and filters above."
            // Named, not drawn: the FAB reads as "+" to the eye and announces "New chat", and
            // this sentence has to be true in both.
            else "Conversations on this machine show up here. Start one with the New chat button.",
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

/**
 * Search, then project / vendor / account / model / sort — inline, always visible, each
 * showing its own current value. Never a "Filters (N)" button.
 *
 * These were `FilterChip` rows: one chip per project inside a horizontal scroller. With
 * three projects that reads as tabs; with thirty the selected one is off screen, so the
 * screen shows a list that is filtered and no way to see by what — and picking a value
 * needs a horizontal scrub through every other value first. A selector renders the
 * selection in place and puts the alternatives in a menu, which is the desktop's
 * repo/model/agent combos exactly.
 */
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

    // A typed query is working state and never collapses. A field over a list that fits on one
    // screen is not, and it was 68 dp of the 130 dp of chrome standing above the first row.
    var opened by rememberSaveable { mutableStateOf(false) }
    val searching = opened || filter.query.isNotEmpty() || rows.size >= SEARCH_WORTH_ROWS
    val field = remember { FocusRequester() }
    // Only on the user's own tap: expanding because the list grew must not raise a keyboard.
    LaunchedEffect(opened) { if (opened) field.requestFocus() }

    Column(Modifier.padding(bottom = 4.dp)) {
        if (searching) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { onFilter(filter.copy(query = it)) },
                placeholder = { Text("Search conversations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onFilter(filter.copy(query = ""))
                                opened = false
                            },
                        ) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear the search")
                        }
                    }
                },
                singleLine = true,
                // A pill on a tonal fill rather than a boxed field: this is a search bar, and the
                // rectangle around it was the loudest edge on the screen above a list of cards.
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .focusRequester(field),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Outside the scroller, like Sort and for the same reason: a control reached by
            // scrubbing past every filter is a control that is off screen.
            if (!searching) {
                IconButton(onClick = { opened = true }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search conversations")
                }
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Sort leads. It is the one control here that is always doing something — every
                // filter starts at "All" — and it was last in a row that scrolls, so on a phone
                // the ordering control was the one thing off screen.
                Selector(
                    options = FleetSort.entries.map { SelectorOption(it.label, it) },
                    selected = sort,
                    onSelect = onSort,
                    prefix = "Sort:",
                )
                // Each filter is hidden while it has one meaningful option — a control that
                // can only be set to what it already says changes no decision.
                if (projects.size > 1) {
                    Selector(
                        options = listOf(SelectorOption<String?>("All projects", null)) +
                            projects.map { SelectorOption<String?>(it.projectName, it.projectPath) },
                        selected = filter.projectPath,
                        onSelect = { onFilter(filter.copy(projectPath = it)) },
                    )
                }
                if (vendors.size > 1) {
                    Selector(
                        options = listOf(SelectorOption<AgentVendor?>("All agents", null)) +
                            vendors.map { SelectorOption<AgentVendor?>(it.label(), it) },
                        selected = filter.vendor,
                        onSelect = { onFilter(filter.copy(vendor = it)) },
                    )
                }
                if (accounts.size > 1) {
                    Selector(
                        options = listOf(SelectorOption<String?>("All accounts", null)) +
                            accounts.map { SelectorOption<String?>(FleetGrouping.accountLabel(rows, it), it) },
                        selected = filter.accountId,
                        onSelect = { onFilter(filter.copy(accountId = it)) },
                    )
                }
                if (models.size > 1) {
                    Selector(
                        options = listOf(SelectorOption<String?>("All models", null)) +
                            models.map { SelectorOption<String?>(it, it) },
                        selected = filter.model,
                        onSelect = { onFilter(filter.copy(model = it)) },
                    )
                }
            }
        }
    }
}

/**
 * One conversation, as a card with a leading agent mark.
 *
 * The old row led with two 11-dp glyphs — the state mark and the vendor character — set in a
 * line of grey metadata, and then ruled the card in half with a divider before three more
 * numbers. Everything below the title was the same weight as everything else. Now the agent is
 * an avatar the eye lands on, its attention state is a badge *on* that avatar, and the numbers
 * are one quiet trailing line with no rule above them: the title and the live line are the
 * only things drawn at reading weight, because they are the only two that decide whether to
 * open it.
 *
 * The whole card carries **one** accessible name — "Claude conversation, <title>, Waiting on
 * you, in Plugin" — because a screen reader walking six separate labels per row cannot triage
 * a list of them. Snooze and Stop ride along as custom actions, since a swipe is a gesture
 * TalkBack does not have.
 */
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
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(14.dp)) {
            RowAvatar(row.vendor, group)
            Column(Modifier.padding(start = 12.dp)) {
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
                    row.title.ifBlank { "(no title)" },
                    style = MaterialTheme.typography.bodyLarge,
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
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    // Cost last. It opened this line and is the least actionable thing on the
                    // row: how far along the conversation is decides whether to open it, what it
                    // cost decides nothing at triage time.
                    buildString {
                        append(row.messageCount).append(" messages")
                        row.contextPct?.let { append(" · ").append(it).append("% context") }
                        append(" · ").append(formatCost(row.costUsd, row.costKnown))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
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

/**
 * Rows above which the search field earns its place on screen.
 *
 * A phone shows about seven of these cards at once, so under that a search is a control over
 * a list the reader can already see whole.
 */
private const val SEARCH_WORTH_ROWS = 8
