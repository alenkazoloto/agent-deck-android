package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileCodexApps

/**
 * The desk's Codex `/apps` opened from a Codex chat's `/` popup: the ChatGPT apps this machine's Codex can
 * call, each with Codex's own state words, its description, the plugins that use it and — when the machine
 * gave an `https` page — a "Manage on ChatGPT" link that opens in the browser. Read-only: an app is
 * installed on the ChatGPT account, and neither the desk nor this app writes it.
 *
 * Read each time the sheet opens. A long list gets the filter the desk's dialog has at the same length.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexAppsSheet(onLoad: suspend () -> MobileCodexApps?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileCodexApps?>(null) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val load by rememberUpdatedState(onLoad)
    val links = LocalUriHandler.current
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("codex-apps-sheet"),
        ) {
            Text("Codex apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val apps = loaded
            when {
                loading -> CodexAppsNote("Reading the apps…")
                apps == null -> CodexAppsNote("The machine did not answer. Close this and try again.")
                apps.notice != null -> CodexAppsNote(apps.notice!!)
                else -> {
                    if (apps.apps.size >= APPS_FILTER_FROM) OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Filter apps") },
                        singleLine = true,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("codex-apps-filter"),
                    )
                    val shown = apps.apps.filter { appMatches(it, filter) }
                    if (shown.isEmpty()) CodexAppsNote("No app matches.")
                    shown.forEachIndexed { index, app ->
                        Column(Modifier.testTag("codex-apps-row-$index").padding(top = 8.dp)) {
                            Text(app.name, style = MaterialTheme.typography.bodyLarge)
                            Text(app.state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (app.description.isNotEmpty()) Text(app.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            if (app.plugins.isNotEmpty()) Text(
                                "Used by " + app.plugins.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            app.manageUrl?.let { url ->
                                TextButton(onClick = { runCatching { links.openUri(url) } }) { Text("Manage ${app.name} on ChatGPT") }
                            }
                            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The desk dialog's filter: a case-insensitive match on the name or the description. */
private fun appMatches(app: MobileCodexApps.App, filter: String): Boolean {
    val needle = filter.trim().lowercase()
    return needle.isEmpty() || app.name.lowercase().contains(needle) || app.description.lowercase().contains(needle)
}

/** The desk dialog's own threshold for showing its filter field. */
private const val APPS_FILTER_FROM = 12

@Composable
private fun CodexAppsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
