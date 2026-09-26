package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileExtension
import com.github.claudeagents.core.mobile.MobileExtensions

/**
 * Settings › Resources › "Extensions": the extensions installed on the machine, read-only.
 *
 * An inventory, not an adapter — an extension's screens and actions run on the desk, so each row
 * says what it adds there ("Adds on the desk: Tool-window tab") rather than offering a control
 * that cannot work here. Read again each time the sheet opens. A switched-off or failing extension
 * says so in words; the machine's own error text never reaches the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionsSheet(onLoad: suspend () -> MobileExtensions?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileExtensions?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Extensions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val result = loaded
            when {
                loading -> Note("Reading the installed extensions…")
                result == null -> Note("The machine did not answer. Close this and try again.")
                result.extensions.isEmpty() -> Note("No extensions installed yet.")
                else -> LazyColumn(Modifier.heightIn(max = 560.dp).testTag("extensions-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(result.extensions, key = { it.id }) { ExtensionRow(it) }
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(extension: MobileExtension) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("extension-row")) {
        Text(extension.name, style = MaterialTheme.typography.bodyLarge)
        Detail(extensionIdentity(extension))
        Detail(extensionStatus(extension))
        extensionAdds(extension)?.let { Detail(it) }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** "1.4.0 · Acme", whichever the extension declared; its id when it declared neither. */
internal fun extensionIdentity(extension: MobileExtension): String =
    listOf(extension.version, extension.vendor).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { extension.id }

/** "On", "Off on the desk", or "On · Paused after repeated errors this session" — a word, never a colour. */
internal fun extensionStatus(extension: MobileExtension): String =
    if (!extension.enabled) "Off on the desk" else listOfNotNull("On", extension.state).joinToString(" · ")

/** "Adds on the desk: Run events, Tool-window tab"; null for an extension that lists no contribution. */
internal fun extensionAdds(extension: MobileExtension): String? =
    extension.contributions.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Adds on the desk: ")
