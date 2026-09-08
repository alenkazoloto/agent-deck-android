package dev.agentdeck.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * One checklist row, shared by markdown `- [x]` and the protocol's own checklist so the two
 * cannot drift into looking like different features on the same screen.
 *
 * The state is carried by the **box's shape and fill** and only then by colour: an empty
 * outline, a filled box with a check, a ring with a centre dot. A reader who cannot separate
 * the tints — greyscale, colour blindness — still has three distinguishable marks, which a
 * tint-only checklist would not give them.
 */
enum class TaskMark { PENDING, ACTIVE, DONE }

@Composable
fun TaskLine(mark: TaskMark, text: AnnotatedString, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TaskBox(mark, Modifier.padding(top = 3.dp, end = 8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            // Struck through and dimmed, the way the desktop paints a finished row: a done
            // list that reads as loudly as the open work is the checklist not doing its job.
            textDecoration = if (mark == TaskMark.DONE) TextDecoration.LineThrough else null,
            color = if (mark == TaskMark.DONE) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (mark == TaskMark.ACTIVE) FontWeight.SemiBold else null,
        )
    }
}

@Composable
fun TaskBox(mark: TaskMark, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val description = when (mark) {
        TaskMark.PENDING -> "To do"
        TaskMark.ACTIVE -> "In progress"
        TaskMark.DONE -> "Done"
    }
    Box(
        modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (mark == TaskMark.DONE) Modifier.background(scheme.primary)
                else Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(4.dp))
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (mark) {
            TaskMark.DONE -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(12.dp),
            )
            TaskMark.ACTIVE -> Box(
                Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary),
            )
            TaskMark.PENDING -> Unit
        }
    }
}
