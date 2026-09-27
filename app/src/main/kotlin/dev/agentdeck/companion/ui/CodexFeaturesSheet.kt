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
import com.github.claudeagents.core.mobile.MobileCodexFeatures

/**
 * The desk's Codex `/experimental` opened from a Codex chat's `/` popup: every feature flag this machine's Codex
 * knows, each with its stage and the state Codex resolved it to ("On · default off" when it was changed), the
 * machine's one-line count above the list and the desk dialog's filter over it. Read-only: turning a flag on
 * writes Codex's own config file on the machine, which neither the desk's dialog nor this app does.
 *
 * Read each time the sheet opens. The list is ~100 rows, so the filter is always there, as on the desk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexFeaturesSheet(onLoad: suspend () -> MobileCodexFeatures?, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<MobileCodexFeatures?>(null) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("codex-features-sheet"),
        ) {
            Text("Codex features", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val features = loaded
            when {
                loading -> CodexFeaturesNote("Reading the features…")
                features == null -> CodexFeaturesNote("The machine did not answer. Close this and try again.")
                features.notice != null -> CodexFeaturesNote(features.notice!!)
                else -> {
                    features.summary?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Filter features") },
                        singleLine = true,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("codex-features-filter"),
                    )
                    val shown = features.features.filter { featureMatches(it, filter) }
                    if (shown.isEmpty()) CodexFeaturesNote("No feature matches this filter.")
                    shown.forEachIndexed { index, feature ->
                        Column(Modifier.testTag("codex-features-row-$index").padding(top = 8.dp)) {
                            Text(feature.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOf(feature.stage, feature.state).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (feature.description.isNotEmpty()) Text(feature.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            if (feature.title != feature.name) Text(feature.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Text(
                        "Codex resolves these from its config.toml — this list is read-only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

/** The desk dialog's filter: a case-insensitive match on the flag, its title, its stage or its description. */
private fun featureMatches(feature: MobileCodexFeatures.Feature, filter: String): Boolean {
    val needle = filter.trim().lowercase()
    return needle.isEmpty() || feature.name.lowercase().contains(needle) || feature.title.lowercase().contains(needle) ||
        feature.stage.lowercase().contains(needle) || feature.description.lowercase().contains(needle)
}

@Composable
private fun CodexFeaturesNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
