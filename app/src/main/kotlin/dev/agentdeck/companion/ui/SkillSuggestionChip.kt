package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileSkillSuggestAnswer

/**
 * The desk's "Suggested: /skill" chip above the message box: the one skill the machine scored the draft against,
 * offered and never run. Insert puts `/name ` before the draft; ✕ leaves that skill out of this chat, as the desk's
 * does. Drawn only where Workspace › Machine's "Suggest matching skills" is on and a Claude chat is open.
 */
@Composable
internal fun SkillSuggestionChip(suggestion: MobileSkillSuggestAnswer, onInsert: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Suggested: ${suggestion.command}",
            Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp).semantics {
                contentDescription = buildString {
                    append("Suggested skill ${suggestion.command}")
                    if (suggestion.description.isNotBlank()) append(". ${suggestion.description}")
                }
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onInsert,
            modifier = Modifier.semantics { contentDescription = "Insert ${suggestion.command} before your message" },
        ) { Text("Insert") }
        DeckIconButton(
            "Don't suggest ${suggestion.command} in this chat. Turn suggestions off with \"Suggest matching skills\" in Workspace › Machine.",
            Icons.Filled.Close,
            onDismiss,
        )
    }
}
