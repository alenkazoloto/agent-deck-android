package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.agentdeck.companion.data.SessionSpendSheet
import dev.agentdeck.companion.data.SpendForm

/**
 * One chat's own spend limits, the desk's "Chat Spend Limits" dialog (`/v1/session-spend`): "Use global
 * defaults" (the fields then show the defaults, read-only), the soft and hard USD limits, the 5-hour and
 * weekly usage percentages that ask for a handoff, the handoff prompt and whether a new chat opens after
 * it. Blank is off, as in the desk's fields; the machine's own sentence answers a form it will not take,
 * and the text stays as typed. On a [SessionSpendSheet.defaults] sheet it is the machine-wide defaults
 * form: no "Use global defaults" row and a heading that says whom the values are for.
 */
@Composable
internal fun SessionSpendDialog(
    sheet: SessionSpendSheet,
    onForm: (SpendForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("session-spend-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(if (sheet.defaults) "Spend defaults" else "Chat spend limits", style = MaterialTheme.typography.headlineSmall)
                    if (sheet.defaults) {
                        Text(
                            "For every chat without limits of its own",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (sheet.title.isNotBlank()) {
                        Text(
                            sheet.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // One scrolling body under the fixed buttons: at 200% text the form outgrows the dialog.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val form = sheet.form
                    if (form == null) {
                        if (sheet.error == null) Notice("Reading this conversation…")
                    } else {
                        Form(form, editable = !sheet.busy, refused = sheet.refused, defaults = sheet.defaults, onForm = onForm)
                    }
                    sheet.error?.let { Notice(it, error = true) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Button(
                        onClick = onSave,
                        enabled = sheet.dirty && !sheet.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("session-spend-save"),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun Form(form: SpendForm, editable: Boolean, refused: String?, defaults: Boolean, onForm: (SpendForm) -> Unit) {
    // The defaults are nobody's override, so there is nothing to opt out of.
    if (!defaults) Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(form.useDefaults, enabled = editable, role = Role.Checkbox) { onForm(form.copy(useDefaults = it)) }
            .testTag("session-spend-defaults"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(form.useDefaults, onCheckedChange = null, enabled = editable)
        Text("Use global defaults", Modifier.padding(start = 8.dp))
    }
    val fields = editable && !form.useDefaults
    Field("Soft limit (USD)", "session-spend-soft", form.soft, fields, decimal = true) { onForm(form.copy(soft = it)) }
    Field("Hard limit (USD)", "session-spend-hard", form.hard, fields, decimal = true) { onForm(form.copy(hard = it)) }
    Field("Hand off at 5-hour usage (%)", "session-spend-session", form.session, fields, decimal = false) { onForm(form.copy(session = it)) }
    Field("Hand off at weekly usage (%)", "session-spend-weekly", form.weekly, fields, decimal = false) { onForm(form.copy(weekly = it)) }
    OutlinedTextField(
        value = form.prompt,
        onValueChange = { onForm(form.copy(prompt = it)) },
        label = { Text("Handoff prompt") },
        enabled = fields,
        minLines = 3,
        maxLines = 8,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().testTag("session-spend-prompt"),
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(form.startNewChat, enabled = fields, role = Role.Checkbox) { onForm(form.copy(startNewChat = it)) }
            .testTag("session-spend-new-chat"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(form.startNewChat, onCheckedChange = null, enabled = fields)
        Text("Start a new chat after the handoff", Modifier.padding(start = 8.dp))
    }
    refused?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("session-spend-refused")) }
}

@Composable
private fun Field(label: String, tag: String, value: String, enabled: Boolean, decimal: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("Off") },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("session-spend-notice"),
    )
}
