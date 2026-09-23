package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.MessageSearch

/**
 * The line under the message hits that says how much was searched. "No match" is only ever said
 * about the chats actually read, so a bounded or interrupted pass reads as one, with a way on.
 */
@Composable
internal fun MessageSearchStatus(search: MessageSearch, hits: Int, onMore: () -> Unit) {
    val (text, action) = messageSearchStatus(search, hits)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (search.searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (search.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.let { label -> TextButton(onClick = onMore) { Text(label) } }
    }
}

/** The status sentence and the button beside it, if any — pure so the wording is tested. */
internal fun messageSearchStatus(search: MessageSearch, hits: Int): Pair<String, String?> {
    val chats = if (search.scanned == 1) "1 chat" else "${search.scanned} chats"
    return when {
        search.searching && search.scanned == 0 -> "Searching messages…" to null
        search.searching -> "Searching older chats…" to null
        search.error != null -> search.error to "Retry"
        // Cut off inside the last chat: nothing left to continue with, and not a clean "no match".
        search.timedOut && search.nextCursor == null -> "Messages searched in $chats; one was too long to finish" to null
        search.timedOut -> "The machine stopped after reading messages in $chats" to "Continue"
        search.nextCursor != null ->
            (if (search.scanned == 1) "Messages searched in the newest chat" else "Messages searched in the ${search.scanned} newest chats") to
                "Search older chats"
        hits == 0 -> "No messages match in $chats" to null
        else -> "Messages searched in $chats" to null
    }
}
