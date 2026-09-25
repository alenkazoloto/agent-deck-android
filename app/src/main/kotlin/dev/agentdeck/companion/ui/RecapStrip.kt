package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileRecap

/**
 * The desk's "where you left off" strip, above the transcript when the reader comes back to a
 * conversation after time away. There is no resting state: it appears only when
 * [dev.agentdeck.companion.data.RecapGate] offers it, and it goes with the ✕, with the first
 * character typed into the message box (a recap standing over a conversation being worked in is
 * a claim about a moment already gone), or with leaving the conversation.
 *
 * "Last active" is written here, in the phone's zone, from the machine's instant.
 */
@Composable
internal fun RecapStrip(recap: MobileRecap, draft: String, onDismiss: () -> Unit) {
    LaunchedEffect(draft.isNotBlank()) { if (draft.isNotBlank()) onDismiss() }
    val line = "Last active ${Times.clock(recap.lastActiveMs, LocalNow.current())}. ${recap.text}"
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).semantics { contentDescription = "Where you left off. $line" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                "Where you left off",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        DeckIconButton(
            "Hide this recap. Turn recaps off with \"Recap after time away\" in the plugin's Settings › Chat.",
            Icons.Filled.Close,
            onDismiss,
        )
    }
}
