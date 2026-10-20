package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.composer.EmojiCompletion

/**
 * The `:` emoji list, stacked above the composer like the `@` and `/` popups. It exists only
 * while it has rows, as on the desk: `:` is common in prose, so an empty list would sit over
 * every "Note:" typed.
 */
@Composable
fun EmojiPopup(
    rows: List<EmojiCompletion.Suggestion>,
    onPick: (EmojiCompletion.Suggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(EMOJI_POPUP_TAG),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            // Names, not glyphs, are unique: `thumbsup` and `thumbs_up` are both 👍.
            items(rows, key = { it.name }) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPick(row) }
                        .padding(horizontal = 12.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "${row.emoji} :${row.name}:" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.emoji, Modifier.width(32.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(":${row.name}:", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
        }
    }
}

const val EMOJI_POPUP_TAG = "emoji-popup"
