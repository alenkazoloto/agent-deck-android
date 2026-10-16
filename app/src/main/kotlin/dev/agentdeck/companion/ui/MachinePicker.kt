package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.PairedMachine

/**
 * The root destinations' machine selector: the name of the machine being shown, and the menu
 * that changes it (PLAN-MOBILE-REDESIGN "Machine selector stays visible at root").
 *
 * Shown with a single pairing too. It used to be an anonymous ⋮ that appeared only at two
 * machines, so every root screen except Chats gave no hint of *whose* work it listed, and
 * adding a second machine meant knowing it lived in Workspace › Machine.
 */
@Composable
fun MachinePicker(
    machines: List<PairedMachine>,
    active: PairedMachine,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = active.displayName()
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Machine: $name. Switch or pair a machine" },
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 148.dp),
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            machines.forEach { machine ->
                DropdownMenuItem(
                    text = { Text(machine.displayName()) },
                    leadingIcon = {
                        if (machine.id == active.id) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        open = false
                        if (machine.id != active.id) onSwitch(machine.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Pair another machine…") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = {
                    open = false
                    onAdd()
                },
            )
        }
    }
}

private fun PairedMachine.displayName(): String = machineName.ifBlank { "Unnamed machine" }
