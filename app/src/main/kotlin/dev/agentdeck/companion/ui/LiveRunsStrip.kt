package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileLiveRuns

/**
 * The desk's "● Running elsewhere: …" bar — the other chats still running, named above the message box, each
 * opening on a tap, so an agent-heavy day does not need the Chats tab to read run state. Drawn only where
 * Settings › Machine's "Running elsewhere" is on, and only while another chat runs; the open chat is never in it,
 * because its own run already shows as Stop. Three names, the rest as "+N more", as the desk trims it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveRunsStrip(runs: List<MobileFleetRow>, onOpen: (MobileFleetRow) -> Unit) {
    if (runs.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "● Running elsewhere:",
            Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = "${runs.size} other chat${if (runs.size == 1) "" else "s"} running"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        runs.take(MobileLiveRuns.MAX_NAMED).forEach { run ->
            Text(
                run.title,
                Modifier.heightIn(min = 48.dp).clickable { onOpen(run) }.semantics {
                    role = Role.Button
                    contentDescription = "Open ${run.title}, running in ${run.projectName}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MobileLiveRuns.overflow(runs)?.let {
            Text(it, Modifier.heightIn(min = 48.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
