package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileUsageAccount
import com.github.claudeagents.core.mobile.MobileUsageBucket
import com.github.claudeagents.core.mobile.MobileUsageCard
import com.github.claudeagents.core.mobile.MobileUsageReport
import com.github.claudeagents.core.mobile.MobileUsageWindow
import com.github.claudeagents.core.spend.Money
import java.util.Locale

/**
 * What this machine has spent, and what its plans still allow.
 *
 * Two questions and nothing else. *"How much has this cost?"* — four periods and the models
 * that drove them. *"Can it still run?"* — every account's live windows, because a plan caps
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
) {
    // Once per entry to the screen, not per composition: the machine walks every indexed
    // conversation to answer, so a recomposition-driven fetch would bill that walk to a scroll.
    LaunchedEffect(Unit) { onLoad() }
    Column(modifier.fillMaxSize().testTag("usage-screen")) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            error != null && report == null -> Notice(error)
            report == null -> if (!loading) Notice("This machine reported no usage.")
            else -> Report(report, staleError = error)
        }
    }
}

@Composable
private fun Report(report: MobileUsageReport, staleError: String?) {
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
            itemsIndexed(report.accounts, key = { _, a -> "account-${a.vendor}-${a.id}" }) { _, a -> AccountCard(a) }
        }
    }
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
private fun CostRow(card: MobileUsageCard) {
    val cost = usageCost(card.usage)
    Card(description = "${card.label}. $cost. ${tokenLine(card.usage)}") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    card.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tokenLine(card.usage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(cost, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AccountCard(account: MobileUsageAccount) {
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
        }
    }
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

private fun shortTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}

private fun MobileUsageWindow.spoken(): String =
    "$label $percent per cent used" + (resetText?.let { ", resets $it" } ?: "")
