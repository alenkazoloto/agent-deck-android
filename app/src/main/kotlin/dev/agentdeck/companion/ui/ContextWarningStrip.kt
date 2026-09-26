package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileContextWarning

/**
 * The desk's "Warn when a chat context is nearly full" banner, above the message box of a conversation past
 * [MobileContextWarning.THRESHOLD]% of its window. Compact and Hand off only fill the reader's own draft,
 * as on the desk — neither is ever sent for them — and a draft or attachment already there is kept, with the
 * desk's sentence saying so, rather than overwritten.
 *
 * Drawn only while [MobileContextWarning.Episodes] offers it: the ✕ hides it until the chat has dropped
 * below the re-arm line (compacted), and the machine's own switch, Settings › Machine › "Context warning",
 * silences it for good.
 */
@Composable
internal fun ContextWarningStrip(
    percent: Int,
    draft: String,
    hasAttachments: Boolean,
    onDraft: (String) -> Unit,
    onHide: () -> Unit,
) {
    val line = MobileContextWarning.line(percent)
    var kept by remember { mutableStateOf(false) }
    fun prepare(prompt: String) {
        when {
            draft == prompt -> Unit
            draft.isEmpty() && !hasAttachments -> onDraft(prompt)
            else -> kept = true
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                line,
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Context window warning. $line" },
                style = MaterialTheme.typography.bodyMedium,
            )
            DeckIconButton(
                "Hide this warning until the chat is compacted. Turn it off with \"Context warning\" in Settings › Machine.",
                Icons.Filled.Close,
                onHide,
            )
        }
        Row {
            TextButton(
                onClick = { prepare(MobileContextWarning.COMPACT_PROMPT) },
                modifier = Modifier.semantics {
                    contentDescription = "Compact: puts ${MobileContextWarning.COMPACT_PROMPT} in your message. Review it, then send."
                },
            ) { Text("Compact") }
            TextButton(
                onClick = { prepare(MobileContextWarning.HANDOFF_PROMPT) },
                modifier = Modifier.semantics {
                    contentDescription = "Hand off: puts an editable handoff request in your message. It is never sent automatically."
                },
            ) { Text("Hand off") }
        }
        if (kept && (draft.isNotEmpty() || hasAttachments)) {
            Text(
                "Your draft and attachments were kept. Clear them before preparing this.",
                Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
