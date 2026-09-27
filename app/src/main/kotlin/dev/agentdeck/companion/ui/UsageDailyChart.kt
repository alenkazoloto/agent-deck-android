package dev.agentdeck.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileUsageDay
import com.github.claudeagents.core.spend.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The desk's "Estimated daily cost" bar chart: one bar per calendar day, the newest selected, the
 * selected day's exact figure in words underneath.
 *
 * **Cost is the bar, the words are the figure.** A bar's height only ranks days; the readout and
 * every bar's spoken description carry the machine's own spelling ([usageCost]), so a day priced
 * off a sibling model's rate reads `~$` and a day with unpriced tokens `≥$`, as on the desk.
 *
 * **Touch reaches a bar by its column, not by its width.** Fourteen bars in a phone's width are
 * about 22dp each, under the 48dp a finger needs, so the whole chart takes the press and follows
 * a drag to the bar under the finger; TalkBack gets each bar as a button of its own.
 */
@Composable
internal fun DailyCostChart(days: List<MobileUsageDay>, modifier: Modifier = Modifier) {
    // Keyed by the window's length: a fresh report with the same window keeps the reader's day,
    // and a machine that changes it starts on the newest again.
    var selected by rememberSaveable(days.size) { mutableIntStateOf(days.lastIndex) }
    val current = days.getOrNull(selected) ?: return
    val peak = days.maxOf { it.usage.costUsd }
    Surface(
        modifier.fillMaxWidth().testTag("usage-daily-chart"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (peak <= 0.0) {
                // The desk's own empty state: an all-zero axis would read as a chart of free days.
                Text(
                    "No cost recorded in the last ${days.size} days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .pointerInput(days.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            selected = barAt(down.position.x, size.width, days.size)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let { selected = barAt(it.position.x, size.width, days.size) }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEachIndexed { i, day ->
                    val on = i == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics(mergeDescendants = true) {
                                contentDescription = dayLine(day)
                                role = Role.Button
                                this.selected = on
                                onClick(label = "Show ${dayLabel(day)}") { selected = i; true }
                            },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        // A day with no spend keeps a hairline, so the axis still has a slot for it.
                        val fraction = (day.usage.costUsd / peak).toFloat().coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(if (fraction > 0f) Modifier.fillMaxHeight(fraction) else Modifier.height(2.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (on) 1f else 0.4f),
                                    RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                ),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dayLabel(days.first(), short = true), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Peak ${Money.usd(peak)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dayLabel(days.last(), short = true), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                dayLine(current),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp).testTag("usage-daily-readout"),
            )
        }
    }
}

private fun barAt(x: Float, width: Int, count: Int): Int =
    if (width <= 0) count - 1 else (x / width * count).toInt().coerceIn(0, count - 1)

/** "Thu, Sep 24 · $1.20 · 1.2M in · 340K out" — the day, the machine's spelling of its cost, its tokens. */
private fun dayLine(day: MobileUsageDay): String =
    if (day.usage.costUsd <= 0.0 && day.usage.input + day.usage.output + day.usage.cacheRead + day.usage.cacheWrite == 0L) {
        "${dayLabel(day)} · no usage"
    } else {
        "${dayLabel(day)} · ${usageCost(day.usage)} · ${tokenLine(day.usage)}"
    }

/** The day in the phone's own locale — the date is the machine's calendar day, its spelling the reader's. */
private fun dayLabel(day: MobileUsageDay, short: Boolean = false): String {
    val date = runCatching { LocalDate.parse(day.day) }.getOrNull() ?: return day.day
    val pattern = if (short) DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT) else DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    return date.format(pattern)
}
