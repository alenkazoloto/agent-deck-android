package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobilePreset
import com.github.claudeagents.core.mobile.MobileRunSelection

/**
 * A bare `/p`: the desk's Presets list over this chat, each row spelling the model, effort and mode a tap puts on the
 * next message — the same words the composer's run line uses, so a pick reads the same before and after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetsSheet(
    hello: MobileHello?,
    vendor: AgentVendor,
    presets: List<MobilePreset>,
    onPick: (MobilePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("presets-sheet")) {
            Text("Presets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "Sets model, effort and mode for this chat's next message.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(presets, key = { it.name }) { preset ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(preset) }
                            .padding(vertical = 8.dp).testTag("preset-${preset.name}"),
                    ) {
                        Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            RunChoiceSummary.of(hello, vendor, MobileRunSelection(preset.model.ifEmpty { null }, preset.effort.ifEmpty { null }, preset.permissionMode)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
