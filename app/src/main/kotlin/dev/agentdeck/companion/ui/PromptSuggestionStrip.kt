package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.composer.PromptSuggestions

/**
 * The desk's prompt-suggestion row: the reply Claude predicts the reader will type next, one line
 * above the composer. Tapping it puts the whole suggestion in the message box — it is never sent
 * for the reader — and the ✕ hides it for this suggestion only.
 *
 * No resting state: the machine offers one only where its owner turned "Suggest the next prompt"
 * on, after a finished turn, and the row goes with the first words typed that it no longer
 * describes ([PromptSuggestions.shouldShow] — the desk's own rule, so a suggestion the reader is
 * already typing is not offered back).
 */
@Composable
internal fun PromptSuggestionStrip(suggestion: String, draft: String, onTake: (String) -> Unit) {
    var hidden by rememberSaveable(suggestion) { mutableStateOf(false) }
    if (hidden || !PromptSuggestions.shouldShow(suggestion, draft)) return
    val text = PromptSuggestions.clean(suggestion) ?: return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            PromptSuggestions.display(text),
            Modifier.weight(1f)
                .clickable(onClickLabel = "Put it in your message", role = Role.Button) { onTake(text) }
                .padding(vertical = 12.dp)
                .semantics { contentDescription = "Suggested reply: $text" },
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        DeckIconButton(
            "Hide this suggestion. Turn suggestions off with \"Suggest the next prompt\" in the plugin's Settings › General.",
            Icons.Filled.Close,
            { hidden = true },
        )
    }
}
