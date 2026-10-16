package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import dev.agentdeck.companion.data.ConversationOutline
import dev.agentdeck.companion.data.OutlineEntry
import dev.agentdeck.companion.data.OutlineKind

/**
 * A long conversation's table of contents.
 *
 * The one gesture a phone cannot do to a transcript is skim it: a thumb covers four turns and a
 * hundred-turn run is a minute of flicking past tool output to find the sentence you wrote.
 * Reading continuity ([ConversationReading]) answers "where was I"; this answers "where is the
 * part I want", which is a different question and needed a list rather than a scroll position.
 *
 * Ranked, not flat: prompts are the chapters a reader actually navigates by, so they are set in
 * the body role at the margin, and the agent's answers and tool runs are indented captions
 * underneath. A flat list of every row would be the transcript again, at a smaller size.
 *
 * **"Start a new chat from this prompt" lives on a prompt row**, which is the one place in the
 * app where a past prompt is both listed and already in memory. It is deliberately not a turn
 * bubble's long-press — a user bubble's text sits in a `SelectionContainer`, and a long-press
 * there belongs to the platform's own copy toolbar — and it is deliberately not a fleet row
 * action, because a fleet row carries a *title*, not the prompt that earned it, and putting the
 * real text on 300 rows of every fleet frame is a payload nothing else on that screen needs.
 */
/**
 * The app bar's glyph for [OutlineSheet], declared beside the sheet it opens.
 *
 * Here rather than at the call site because `MainActivity` already imports the *unmirrored*
 * `Icons.Filled.List` for the Fleet tab, and two `List` properties cannot share one file's
 * import list — a rename at the call site would have been an alias nobody could read.
 */
val OutlineIcon: ImageVector get() = Icons.AutoMirrored.Filled.List

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    outline: ConversationOutline,
    onDismiss: () -> Unit,
    onJump: (OutlineEntry) -> Unit,
    onStartFrom: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("conversation-outline")) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("Outline", style = MaterialTheme.typography.titleMedium)
                Text(
                    counts(outline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(outline.entries, key = { "${it.turnId}-${it.kind}" }) { entry ->
                    OutlineRow(entry, onJump = { onJump(entry) }, onStartFrom = onStartFrom)
                }
            }
        }
    }
}

/**
 * What the header counts, and what it says it counted.
 *
 * "in view" rather than "in this conversation": the phone holds the newest 120 turns and the
 * plugin's session index has never counted tool calls for any surface, the desktop included —
 * so a bare total here would be a number the reader could not reconcile with anything.
 */
private fun counts(outline: ConversationOutline): String {
    val prompts = "${outline.prompts} prompt${if (outline.prompts == 1) "" else "s"}"
    val tools = "${outline.toolCalls} tool call${if (outline.toolCalls == 1) "" else "s"}"
    return "$prompts · $tools in view"
}

@Composable
private fun OutlineRow(entry: OutlineEntry, onJump: () -> Unit, onStartFrom: (String) -> Unit) {
    val chapter = entry.kind == OutlineKind.PROMPT
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onJump)
            .padding(start = if (chapter) 20.dp else 36.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                entry.label,
                style = if (chapter) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodySmall,
                fontWeight = if (chapter) FontWeight.SemiBold else FontWeight.Normal,
                color = if (chapter) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (chapter) {
            DeckIconButton(
                label = "Start a new chat from this prompt",
                icon = Icons.Filled.Add,
                onClick = { onStartFrom(entry.source ?: entry.label) },
            )
        }
    }
}
