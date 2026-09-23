package dev.agentdeck.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileReviewFeedbackChip
import com.github.claudeagents.core.mobile.MobileReviewFeedbackPending

/** What the composer needs of review feedback; null where the machine does not advertise `review-feedback-chips`. */
class ReviewFeedbackUi(
    val chips: List<MobileReviewFeedbackChip>,
    val onRetry: (MobileReviewFeedbackChip) -> Unit,
    val onRemove: (MobileReviewFeedbackChip) -> Unit,
)

/**
 * The desk composer's review-feedback chips: one per file of attached notes, named with its note
 * count and, for feedback a run did not take, its delivery state and a Retry. The label opens the
 * notes the send carries; ✕ takes that file's notes out of the feedback and leaves them saved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewFeedbackChips(feedback: ReviewFeedbackUi) {
    if (feedback.chips.isEmpty()) return
    var reading by remember { mutableStateOf<MobileReviewFeedbackChip?>(null) }
    // Wrapped, not scrolled: at 200% text a sideways row hides the state a reader must see before Send.
    FlowRow(
        Modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        feedback.chips.forEach { chip ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("feedback-chip"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chipLabel(chip),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (chip.retryable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Show the review notes", role = Role.Button) { reading = chip }
                            .padding(start = 12.dp, top = 14.dp, bottom = 14.dp),
                    )
                    if (chip.retryable) {
                        DeckIconButton("Retry saved review feedback", Icons.Filled.Refresh, onClick = { feedback.onRetry(chip) })
                    }
                    DeckIconButton("Remove this file's review feedback from the message", Icons.Filled.Close,
                        onClick = { feedback.onRemove(chip) })
                }
            }
        }
    }
    reading?.let { chip ->
        AlertDialog(
            onDismissRequest = { reading = null },
            title = { Text(chip.path) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    chip.notes.forEach { note ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                (if (note.startLine == note.endLine) "Line ${note.startLine}" else "Lines ${note.startLine}–${note.endLine}") +
                                    if (note.side == com.github.claudeagents.core.mobile.MobileReviewNote.BASELINE) " · before the chat" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(note.quote, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(note.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reading = null }) { Text("Close") } },
        )
    }
}

/** "Noted.kt · 2 notes · not delivered" — the desk chip's words, with the file's name for a phone's width. */
fun chipLabel(chip: MobileReviewFeedbackChip): String {
    val count = chip.notes.size
    val status = when (chip.state) {
        MobileReviewFeedbackPending.FAILED -> " · not delivered"
        MobileReviewFeedbackPending.UNKNOWN -> " · delivery unknown"
        else -> ""
    }
    return "${chip.path.substringAfterLast('/')} · $count ${if (count == 1) "note" else "notes"}$status"
}
