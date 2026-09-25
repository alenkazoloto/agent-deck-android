package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileInsights
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileUsageAccount
import com.github.claudeagents.core.mobile.MobileUsageBucket
import com.github.claudeagents.core.mobile.MobileUsageCap
import com.github.claudeagents.core.mobile.MobileUsageCard
import com.github.claudeagents.core.mobile.MobileUsageFilter
import com.github.claudeagents.core.mobile.MobileUsageProject
import com.github.claudeagents.core.mobile.MobileUsageReport
import com.github.claudeagents.core.mobile.MobileUsageWindow
import com.github.claudeagents.core.spend.Money
import dev.agentdeck.companion.data.AfterReset
import dev.agentdeck.companion.data.LimitContinuation
import java.util.Locale

/**
 * What this machine has spent, and what its plans still allow.
 *
 * Two questions and nothing else. *"How much has this cost?"* — four periods and the models
 * and projects that drove them. *"Can it still run?"* — every account's live windows, because a plan caps
 * several of them on separate clocks and an account at 12% of its 5-hour window can still be
 * four days from its next weekly token.
 *
 * Every number here was computed on the machine, including the dollar *spelling*: `~$` means
 * part of it was priced off a sibling model's rate and `≥$` means unpriced tokens were folded
 * in, and a phone that re-rounded would print `$0.00` over a day that had spend
 * ([Money]). The reset times are the machine's wall clock, in the machine's zone, for the same
 * reason — a plan resets where the plan is, not where the reader is standing.
 */
@Composable
fun UsageScreen(
    report: MobileUsageReport?,
    loading: Boolean,
    error: String?,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
    /** The desk's Agent / Account filter: the machine re-cuts the spend to the pick; null is "all". */
    onFilter: (agent: AgentVendor?, account: String?) -> Unit = { _, _ -> },
    /** For the resets a drained plan can queue a prompt against; null offers none. */
    hello: MobileHello? = null,
    /** The desk's "Continue after reset": Schedule's create dialog, this account and its reset picked. */
    onScheduleAfterReset: (vendor: AgentVendor, accountId: String) -> Unit = { _, _ -> },
    /** The desk's Settings › Spending: the machine-wide limits a chat without its own runs on; offered while `hello` advertises them. */
    onEditSpendDefaults: () -> Unit = {},
    /** The desk's "Set active": new chats of the agent run on the account; offered while `hello` advertises it. */
    onSetActiveAccount: (vendor: AgentVendor, accountId: String) -> Unit = { _, _ -> },
    /** The desk's Export › CSV: the shown cut as a file through Android's share sheet; offered while `hello` advertises it. */
    onExport: () -> Unit = {},
    /** The desk's Session insights: a month of the machine's sessions summed, read when the sheet opens; offered while `hello` advertises it. */
    onLoadInsights: suspend () -> MobileInsights? = { null },
) {
    // Once per entry to the screen, not per composition: the machine walks every indexed
    // conversation to answer, so a recomposition-driven fetch would bill that walk to a scroll.
    LaunchedEffect(Unit) { onLoad() }
    val setActive = MobileProtocol.Capability.ACTIVE_ACCOUNT in hello?.capabilities.orEmpty()
    Column(modifier.fillMaxSize().testTag("usage-screen")) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            error != null && report == null -> Notice(error)
            report == null -> if (!loading) Notice("This machine reported no usage.")
            else -> Report(
                report,
                staleError = error,
                onFilter = onFilter,
                onEditSpendDefaults = onEditSpendDefaults.takeIf { MobileProtocol.Capability.SESSION_SPEND_DEFAULTS in hello?.capabilities.orEmpty() },
                onExport = onExport.takeIf { MobileProtocol.Capability.USAGE_EXPORT in hello?.capabilities.orEmpty() },
                onLoadInsights = onLoadInsights.takeIf { MobileProtocol.Capability.SESSION_INSIGHTS in hello?.capabilities.orEmpty() },
            ) { account ->
                // The desk lists its account choices from the second account of an agent on: one has nothing to switch to.
                if (setActive && !account.active && report.accounts.count { it.vendor == account.vendor } > 1) {
                    SetActive(account) { onSetActiveAccount(account.vendor, account.id) }
                }
                val reset = LimitContinuation.resetOf(hello, account.vendor, account.id, LocalNow.current())
                reset?.let { ScheduleAfterReset(it) { onScheduleAfterReset(account.vendor, account.id) } }
            }
        }
    }
}

@Composable
private fun Report(
    report: MobileUsageReport,
    staleError: String?,
    onFilter: (AgentVendor?, String?) -> Unit,
    onEditSpendDefaults: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onLoadInsights: (suspend () -> MobileInsights?)?,
    accountAction: @Composable (MobileUsageAccount) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Above the figures, not below: it is a qualification *of* them, and a reader who has
        // already read "Today $0.31" and moved on will not come back for a footnote.
        staleError?.let { item(key = "stale") { Notice("Showing the last figures — $it") } }
        if (report.indexing) {
            item(key = "indexing") {
                Notice("This machine is still reading its transcripts, so these totals will grow.")
            }
        }
        if (report.filter.offered) item(key = "filter") { UsageFilterRow(report.filter, onFilter) }
        if (report.cards.isNotEmpty()) {
            item(key = "spend-head") { SectionHead("Spend") }
            itemsIndexed(report.cards, key = { i, c -> "spend-$i-${c.label}" }) { _, card -> CostRow(card) }
        }
        if (report.models.isNotEmpty()) {
            item(key = "models-head") { SectionHead("By model") }
            itemsIndexed(report.models, key = { i, c -> "model-$i-${c.label}" }) { _, card -> CostRow(card) }
        }
        if (report.accounts.isNotEmpty()) {
            item(key = "plans-head") { SectionHead("Plans") }
            itemsIndexed(report.accounts, key = { _, a -> "account-${a.vendor}-${a.id}" }) { _, a -> AccountCard(a) { accountAction(a) } }
        }
        // Beside Plans, the other half of "can it still run?": the caps that stop a run, whoever set them.
        if (report.caps.isNotEmpty() || onEditSpendDefaults != null) {
            item(key = "caps-head") { SectionHead("Spend limits") }
            itemsIndexed(report.caps, key = { i, c -> "cap-$i-${c.title}" }) { _, cap -> CapRow(cap) }
            onEditSpendDefaults?.let { edit ->
                item(key = "caps-edit") {
                    TextButton(
                        onClick = edit,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("usage-edit-spend-defaults"),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) { Text("Edit spend defaults…") }
                }
            }
        }
        // After Plans, like the projects below: a drained window is the one thing here a reader acts
        // on, and a 96dp chart above it would push it off the first screen. The 30-day card says how
        // much; the bars say which days.
        if (report.days.isNotEmpty()) {
            item(key = "days-head") { SectionHead("Daily cost — last ${report.days.size} days") }
            item(key = "days-chart") { DailyCostChart(report.days) }
        }
        // A table of eight projects above Plans would push a drained window off the first screen too.
        if (report.projects.isNotEmpty()) {
            item(key = "projects-head") { SectionHead("By project") }
            itemsIndexed(report.projects, key = { i, p -> "project-$i-${p.name}" }) { _, project ->
                CostRow(
                    MobileUsageCard(project.name, project.usage),
                    detail = "${sessionCount(project.sessions)} · ${tokenLine(project.usage)}",
                )
            }
        }
        // After the figures it sums, before the export that hands them over as a file.
        onLoadInsights?.let { load ->
            item(key = "insights") {
                var open by rememberSaveable { mutableStateOf(false) }
                TextButton(
                    onClick = { open = true },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("usage-insights"),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) { Text("Session insights…") }
                if (open) InsightsSheet(onLoad = load, onDismiss = { open = false })
            }
        }
        // Last: it hands over everything above as a file, so it reads after the figures it exports.
        onExport?.let { export ->
            item(key = "export") {
                TextButton(
                    onClick = export,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("usage-export"),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) { Text("Export usage as CSV…") }
            }
        }
    }
}

/**
 * Inline, like Chats' project and agent chips: a filter that narrows what is on the page belongs on
 * it, not behind a menu. Each row offers only what the machine lists — one agent or one account
 * has nothing to tell apart — and a chip's tick, not its colour alone, says which is on.
 */
@Composable
private fun UsageFilterRow(filter: MobileUsageFilter, onFilter: (AgentVendor?, String?) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("usage-filter"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (filter.agents.isNotEmpty()) {
            ChipRow("usage-filter-agent") {
                FilterOption("All agents", filter.agent == null, "usage-filter-agent-all") { onFilter(null, filter.account) }
                filter.agents.forEach { agent ->
                    FilterOption(agentName(agent), filter.agent == agent, "usage-filter-agent-${agent.name}") { onFilter(agent, filter.account) }
                }
            }
        }
        if (filter.accounts.isNotEmpty()) {
            ChipRow("usage-filter-account") {
                FilterOption("All accounts", filter.account == null, "usage-filter-account-all") { onFilter(filter.agent, null) }
                filter.accounts.forEach { account ->
                    FilterOption(account.label, filter.account == account.id, "usage-filter-account-${account.id}") { onFilter(filter.agent, account.id) }
                }
            }
        }
    }
}

private fun agentName(vendor: AgentVendor): String = vendor.name.lowercase().replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(tag: String, content: @Composable () -> Unit) {
    // Wrapped, not scrolled: a chip off the edge is a choice the reader never learns exists, and a
    // second scroll axis inside the page's own list fights the drag that reads it.
    FlowRow(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun FilterOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    )
}

@Composable
private fun SectionHead(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun CostRow(card: MobileUsageCard, detail: String = tokenLine(card.usage)) {
    val cost = usageCost(card.usage)
    Card(description = "${card.label}. $cost. $detail") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    card.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(cost, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** No bar: a budget has no window to fill and a gateway never states its amount, so the row is words. */
@Composable
private fun CapRow(cap: MobileUsageCap) {
    Card(description = "${cap.title}. ${cap.value}. ${cap.detail}") {
        // Stacked, not side by side: a budget's value ("hands off at $5.00 · stops at $10.00") is as
        // long as its title and would squeeze it to two lines.
        Text(cap.title, style = MaterialTheme.typography.titleSmall)
        Text(cap.value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
        if (cap.detail.isNotEmpty()) {
            Text(
                cap.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AccountCard(account: MobileUsageAccount, action: @Composable () -> Unit) {
    val heading = account.label + if (account.active) " · active" else ""
    Card(description = "$heading. " + (account.note ?: account.windows.joinToString(". ") { it.spoken() })) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    account.vendor.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            account.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            account.windows.forEach { WindowRow(it) }
            action()
        }
    }
}

/** The desk's "Set active": open chats keep their account, so the label names what it changes. */
@Composable
private fun SetActive(account: MobileUsageAccount, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).testTag("usage-set-active-${account.vendor.name}-${account.id}"),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) { Text("Set active for new chats") }
}

/**
 * Only on a drained plan, which is the one state the reader cannot fix from here: the work waits
 * for the reset, and this queues it for then instead of leaving them to come back.
 */
@Composable
private fun ScheduleAfterReset(reset: AfterReset, onClick: () -> Unit) {
    val nowMs = LocalNow.current()
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).testTag("usage-schedule-after-reset"),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) { Text("Schedule a prompt for ${Times.clock(reset.dueAtMs(), nowMs)}…") }
}

@Composable
private fun WindowRow(window: MobileUsageWindow) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            window.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        window.resetText?.let {
            Text(
                "resets $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        Text(
            "${window.percent}%",
            style = MaterialTheme.typography.titleSmall,
            // A drained window is the one fact on this screen a reader must act on, so it is
            // the one thing coloured. Everything else stays in the body palette.
            color = if (window.reached) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Card(description: String, content: @Composable () -> Unit) {
    Surface(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

/** The machine's own spelling of a figure, including what it does not know about it. */
fun usageCost(usage: MobileUsageBucket): String =
    Money.bucket(usage.costUsd, usage.costKnown, usage.costEstimated)

/** "1.2M in · 340K out · 45.0M cached" — cache folded into one number; the split is desk work. */
fun tokenLine(usage: MobileUsageBucket): String = buildString {
    append(shortTokens(usage.input)).append(" in · ")
    append(shortTokens(usage.output)).append(" out")
    val cached = usage.cacheRead + usage.cacheWrite
    if (cached > 0) append(" · ").append(shortTokens(cached)).append(" cached")
}

private fun sessionCount(n: Int): String = if (n == 1) "1 session" else "$n sessions"

private fun shortTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}

private fun MobileUsageWindow.spoken(): String =
    "$label $percent per cent used" + (resetText?.let { ", resets $it" } ?: "")
