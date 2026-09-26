package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileBadge
import com.github.claudeagents.core.mobile.MobileBadges
import dev.agentdeck.companion.data.BadgeShare
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings › Resources › "Badges": the desk's badge gallery, read-only, with Share on an earned one.
 *
 * Read again each time the sheet opens, so a tier earned while the phone was away is there. Every
 * state is a word — "Gold", "Not earned yet", "NEW" — and progress is a sentence ("740 of 1 000
 * files to Silver"), not a ring, so nothing depends on colour. Share hands the desk's own line to
 * Android's share sheet; a locked badge has nothing to share, so it has no button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BadgesSheet(onLoad: suspend () -> MobileBadges?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileBadges?>(null) }
    var loading by remember { mutableStateOf(true) }
    var noShareApp by remember { mutableStateOf(false) }
    val load by rememberUpdatedState(onLoad)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Badges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val result = loaded
            when {
                loading -> Note("Reading your badges…")
                result == null -> Note("The machine did not answer. Close this and try again.")
                result.unavailable != null && result.badges.isEmpty() -> Note(result.unavailable!!)
                else -> {
                    Detail("${result.earned} of ${result.badges.size} badges earned")
                    if (noShareApp) Note("No app on this phone can receive shared text.")
                    LazyColumn(Modifier.heightIn(max = 560.dp).testTag("badges-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(result.badges, key = { it.id }) { badge ->
                            BadgeRow(badge) { text ->
                                noShareApp = runCatching { context.startActivity(BadgeShare.chooser(badge.name, text)) }.isFailure
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeRow(badge: MobileBadge, onShare: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("badge-row")) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${badge.emoji} ${badge.name}", style = MaterialTheme.typography.bodyLarge)
                Detail(badgeStatus(badge))
                Detail(badge.caption)
                badgeProgress(badge)?.let { Detail(it) }
            }
            badge.shareText?.let { text ->
                DeckIconButton("Share ${badge.name}", Icons.Filled.Share, onClick = { onShare(text) })
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** "Gold · earned 12 Mar 2026 · NEW", or "Not earned yet" — the tier is a word, never a colour. */
internal fun badgeStatus(badge: MobileBadge, zone: ZoneId = ZoneId.systemDefault()): String {
    val tier = badge.tier ?: return "Not earned yet"
    val date = badge.unlockedAtMs.takeIf { it > 0 }?.let {
        "earned " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(it).atZone(zone))
    }
    return listOfNotNull(tier, date, "NEW".takeIf { badge.isNew }).joinToString(" · ")
}

/** "740 of 1 000 files to Silver"; null once Diamond is earned, when there is nothing left to reach. */
internal fun badgeProgress(badge: MobileBadge): String? {
    val next = badge.nextTier ?: return null
    val target = badge.nextTarget ?: return null
    return "${badge.value} of $target ${badge.unit} to $next"
}
