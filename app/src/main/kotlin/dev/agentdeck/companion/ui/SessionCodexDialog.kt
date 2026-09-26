package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileSessionCodexControl
import com.github.claudeagents.core.mobile.MobileSessionCodexOption
import dev.agentdeck.companion.data.SessionCodexSheet

/**
 * A Codex chat's own settings, the desk composer's communication-style, web-search and config-profile
 * buttons (`/v1/session-codex`): three groups of single-choice rows, each tap one change the machine
 * answers. Under each group the machine says whether a reply sent from the phone uses the choice, and the
 * web modes that reach the open web say they apply to the desk's replies alone.
 */
@Composable
internal fun SessionCodexDialog(
    sheet: SessionCodexSheet,
    onChoose: (control: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("session-codex-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("Codex settings", style = MaterialTheme.typography.headlineSmall)
                    if (sheet.title.isNotBlank()) {
                        Text(
                            sheet.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // One scrolling body under the fixed button: three groups outgrow the dialog at 200% text.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val controls = sheet.controls
                    if (controls == null && sheet.error == null) Notice("Reading this conversation…")
                    controls?.forEach { control -> ControlGroup(control, enabled = !sheet.busy, onChoose = { onChoose(control.id, it) }) }
                }
                // Outside the scroll: a refusal of the change just tapped is seen wherever the list was scrolled to.
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    sheet.refused?.let { Notice(it, error = true, tag = "session-codex-refused") }
                    sheet.error?.let { Notice(it, error = true) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun ControlGroup(control: MobileSessionCodexControl, enabled: Boolean, onChoose: (String) -> Unit) {
    Text(
        control.title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).semantics { heading() }.testTag("session-codex-group"),
    )
    Column(Modifier.selectableGroup()) {
        control.options.forEach { option ->
            OptionRow(option, selected = option.value == control.chosen, enabled = enabled, onClick = { onChoose(option.value) })
        }
    }
    if (control.note.isNotBlank()) Notice(control.note, tag = "session-codex-note")
}

@Composable
private fun OptionRow(option: MobileSessionCodexOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick,
        ).padding(horizontal = 12.dp).testTag("session-codex-option"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            if (option.detail.isNotBlank()) {
                Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (option.widens) {
                Text(
                    "Applies to replies typed at the desk only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("session-codex-desk-only"),
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false, tag: String = "session-codex-notice") {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp).testTag(tag),
    )
}
