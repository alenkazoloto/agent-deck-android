package dev.agentdeck.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
                snapshot?.usageLine?.let { usage ->
                    item {
                        Text(
                            usage,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                // Nothing this app hides is hidden silently. The count is here, and tapping
                // it brings every snoozed row straight back.
                if (hiddenCount > 0) {
                    item(key = "snoozed") { SnoozedRow(hiddenCount) }
                }

                if (sections.isEmpty()) {
                    item {
                        Text(
                            if (rows.isEmpty()) "No conversations on this machine yet."
                            else "No conversations match these filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
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
        FleetRow(row, group, selected, onOpen, onDetails, onSnooze, onStop)
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
 * A group's heading, as a pill rather than a line of text.
 *
 * It is sticky, so it is drawn *over* the rows it labels; as bare text on a `surface` band it
 * read as a row that had lost its card. The pill is opaque and shaped, so what scrolls under
 * it stays legible as something else.
 */
@Composable
private fun SectionHeader(title: String, total: Int, group: FleetGroup?) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group?.let { StateMark(it) }
                Text(
                    "$title · $total",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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

    Column(Modifier.padding(bottom = 4.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onFilter(filter.copy(query = it)) },
            placeholder = { Text("Search conversations") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(onClick = { onFilter(filter.copy(query = "")) }) {
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
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
                    Text(
                        Times.relative(row.lastActivityMs),
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
 * The badge is [StateMark] unchanged — the shape-plus-tint pair `FleetTriageTest` pins — over a
 * `surface`-coloured ring, so a dark mark on a dark avatar keeps an edge. Neither mark carries
 * its own description any more: the whole row announces once, and a vendor label repeated
 * inside a merged node is read twice.
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
                .size(17.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            // Why the row is where it is, on the row — the section heading that used to be the
            // only carrier of this is off screen for most of a long list.
            StateMark(group)
        }
    }
}
