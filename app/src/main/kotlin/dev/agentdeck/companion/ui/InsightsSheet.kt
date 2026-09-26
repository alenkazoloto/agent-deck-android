package dev.agentdeck.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileInsights
import com.github.claudeagents.core.mobile.MobileInsightsTable
import com.github.claudeagents.core.mobile.MobileInsightsTile

/**
 * Settings › Usage › "Session insights…": the desk Usage tab's `/insights` section — a month of this
 * machine's sessions summed — read-only.
 *
 * Read each time the sheet opens, under the Agent / Account pick the Usage screen holds, so it counts
 * what the figures behind it count. Every sentence, tile and row is the machine's own text; the phone
 * lays them out and adds nothing. A table becomes rows — the name over its numbers in words ("Runs 4 ·
 * Tokens 4.0K") — because a five-column grid does not fit a phone at 200% text. The desk's cache-miss
 * rows open the turn in its chat; that jump is not offered here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsSheet(onLoad: suspend () -> MobileInsights?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileInsights?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("insights-sheet")) {
            Text("Session insights", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val insights = loaded
            when {
                loading -> InsightsNote("Reading this machine's sessions…")
                insights == null -> InsightsNote("The machine did not answer. Close this and try again.")
                else -> InsightsBody(insights)
            }
        }
    }
}

@Composable
private fun InsightsBody(insights: MobileInsights) {
    LazyColumn(Modifier.testTag("insights-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "summary") { InsightsDetail(insights.summary) }
        if (insights.indexing) item(key = "indexing") { InsightsNote("This machine is still reading its transcripts, so these totals will grow.") }
        if (insights.empty) return@LazyColumn
        itemsIndexed(insights.tiles.chunked(2), key = { i, _ -> "tiles-$i" }) { _, pair ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { Tile(it, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        if (insights.hours.size == HOURS_PER_DAY && insights.hoursCaption != null) {
            item(key = "hours") { HourBars(insights.hours, insights.hoursCaption!!) }
        }
        insights.tables.forEach { table ->
            item(key = "caption-${table.id}") { InsightsDetail(table.caption, Modifier.padding(top = 16.dp)) }
            items(table.rows, key = null) { row -> TableRow(table, row) }
        }
        insights.footer?.let { item(key = "footer") { InsightsDetail(it, Modifier.padding(top = 16.dp)) } }
    }
}

@Composable
private fun Tile(tile: MobileInsightsTile, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = tile.tooltip?.let { "${tile.value} ${tile.caption}. $it" } ?: "${tile.value} ${tile.caption}" }.testTag("insights-tile")) {
        Text(tile.value, style = MaterialTheme.typography.titleMedium)
        InsightsDetail(tile.caption)
    }
}

/** 24 bars, one per local hour of the machine's day; the caption is the words, the bars only rank the hours. */
@Composable
private fun HourBars(hours: List<Int>, caption: String) {
    val peak = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).semantics(mergeDescendants = true) { contentDescription = caption }.testTag("insights-hours")) {
        InsightsDetail(caption)
        Row(Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            hours.forEach { worked ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    // A quiet hour keeps a hairline, so the axis still has a slot for it.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(if (worked > 0) Modifier.fillMaxHeight(worked.toFloat() / peak) else Modifier.height(2.dp))
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = if (worked > 0) 1f else 0.25f),
                                RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00").forEach { InsightsDetail(it) }
        }
    }
}

@Composable
private fun TableRow(table: MobileInsightsTable, row: List<String>) {
    Column(Modifier.fillMaxWidth().testTag("insights-row-${table.id}")) {
        Text(row.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        InsightsDetail(insightsRowDetail(table, row))
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun InsightsDetail(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun InsightsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}

/** "Runs 4 · Tokens 4.0K · Per run 1.0K": each cell after the name under its column's own heading. */
internal fun insightsRowDetail(table: MobileInsightsTable, row: List<String>): String =
    row.drop(1).withIndex().joinToString(" · ") { (i, cell) -> "${table.columns.getOrNull(i + 1).orEmpty()} $cell".trim() }

private const val HOURS_PER_DAY = 24
