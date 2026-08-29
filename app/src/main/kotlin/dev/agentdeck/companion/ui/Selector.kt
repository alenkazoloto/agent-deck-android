package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One choice in a [Selector]: what it is called, and the value it selects. */
data class SelectorOption<T>(val label: String, val value: T)

/**
 * The phone's equivalent of a desktop filter combo.
 *
 * It renders its **current value**, not a count — a control the user acts on may never hide
 * what it is set to behind a summary (`CLAUDE.md`, "Never collapse working state behind a
 * summary control"). Chips could not do that: with 30 projects the selected one scrolls off
 * a chip row, so the screen showed a filter that was on and no way to see which.
 *
 * The label is trimmed rather than wrapped, and the button is sized to the selection, so a
 * row of these stays one line whatever the values are.
 */
@Composable
fun <T> Selector(
    options: List<SelectorOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Names what the value *is* when the value alone cannot. The filters say "All projects"
     * and carry their own noun; a sort says "Attention", which beside them reads as one more
     * filter. On the button only — inside the menu the heading is already established.
     */
    prefix: String? = null,
) {
    if (options.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected } ?: options.first()

    // A tonal pill, not an outlined button: these sit under a pill-shaped search field and over
    // a list of tonal cards, and an outline is the loudest edge either of them has. Whether a
    // filter is *set* is carried by the label it renders, which is the whole point of the
    // control — so the container never has to shout to say "on".
    Row(modifier) {
        Button(
            onClick = { open = true },
            shape = CircleShape,
            contentPadding = SelectorPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            // A tonal pill this size reads as a small control, and it was one: Material's
            // button floor is 40 dp and the padding here trims it no further, so every filter
            // on the fleet sat 8 dp under the platform's touch minimum. The height is raised
            // rather than the padding, so the pill keeps its shape.
            modifier = Modifier
                .heightIn(min = MIN_TARGET)
                // Without this the row announces its label and nothing else — "All projects",
                // with no hint that it is a control or what changing it does.
                .semantics {
                    role = Role.DropdownList
                    contentDescription = prefix?.let { "$it: ${current.label}" } ?: current.label
                },
        ) {
            Text(
                prefix?.let { "$it ${current.label}" } ?: current.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}

/** Android's minimum touch target. Every control the fleet's filter row draws clears it. */
private val MIN_TARGET = 48.dp

private val SelectorPadding = androidx.compose.foundation.layout.PaddingValues(
    start = 12.dp,
    end = 4.dp,
    top = 4.dp,
    bottom = 4.dp,
)
