package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileInitSetup
import com.github.claudeagents.core.mobile.MobileInitSetupRequest
import kotlinx.coroutines.launch

/**
 * The desk's `/init` opened from a chat's `/` popup: its "Set Up This Project" picker — what to set up, where
 * the instructions go, what to do about a file that already exists, the other tools' instructions to carry
 * across, and notes — in the desk's words. "Propose" hands the picks to the machine, which composes the desk's
 * prompt; the phone sends it as an ordinary message, so the agent proposes before it writes, as on the desk.
 *
 * The picks survive closing and a failed Propose ([draft] / [onDraft]); only a sent prompt clears them.
 * [notes] are the words typed after `/init` this time, newer than a parked draft's, so they win.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InitSheet(
    notes: String,
    draft: MobileInitSetupRequest?,
    onDraft: (MobileInitSetupRequest?) -> Unit,
    onLoad: suspend () -> MobileInitSetup?,
    onSubmit: suspend (MobileInitSetupRequest) -> String?,
    onDismiss: () -> Unit,
) {
    var loaded by remember { mutableStateOf<MobileInitSetup?>(null) }
    var loading by remember { mutableStateOf(true) }
    var picks by remember { mutableStateOf((draft ?: MobileInitSetupRequest("")).let { if (notes.isEmpty()) it else it.copy(notes = notes) }) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val load by rememberUpdatedState(onLoad)
    val submit by rememberUpdatedState(onSubmit)
    val scope = rememberCoroutineScope()
    val edit = { next: MobileInitSetupRequest ->
        picks = next
        onDraft(next)
    }
    LaunchedEffect(Unit) {
        loaded = load()
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("init-sheet")) {
            Text("Set up this project", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val setup = loaded
            when {
                loading -> InitNote("Reading the project…")
                setup == null -> InitNote("The machine did not answer. Close this and try again.")
                setup.notice != null -> InitNote(setup.notice!!)
                else -> {
                    val file = setup.instructionsFile
                    InitCheck("init-instructions", file, picks.instructions) { edit(picks.copy(instructions = it)) }
                    InitCheck("init-skills", "Skills", picks.skills) { edit(picks.copy(skills = it)) }
                    InitCheck("init-hooks", "Hooks", picks.hooks) { edit(picks.copy(hooks = it)) }
                    if (picks.instructions) {
                        InitHeading("Where")
                        InitRadio("init-where-project", "This repository — $file", !picks.personal) { edit(picks.copy(personal = false)) }
                        InitRadio("init-where-personal", "Just me — ${setup.personalPath}", picks.personal) { edit(picks.copy(personal = true)) }
                        // A personal file is never the repository's existing one, so the choice only applies to the repository's.
                        if (setup.hasInstructions && !picks.personal) {
                            InitHeading("Existing file")
                            InitRadio("init-existing-improve", "Review and improve it", !picks.replace) { edit(picks.copy(replace = false)) }
                            InitRadio("init-existing-replace", "Start fresh", picks.replace) { edit(picks.copy(replace = true)) }
                        }
                    }
                    if (setup.sources.isNotEmpty()) {
                        InitHeading("Carry across")
                        Text(
                            "Instructions other tools already keep in this repository.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        setup.sources.forEach { source ->
                            InitCheck("init-import-${source.path}", source.label, source.path in picks.imports) { on ->
                                edit(picks.copy(imports = if (on) picks.imports + source.path else picks.imports - source.path))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = picks.notes,
                        onValueChange = { edit(picks.copy(notes = it)) },
                        label = { Text("Notes") },
                        supportingText = { Text("Optional — anything the agent would not learn from the code.") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("init-notes"),
                    )
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("init-error"))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                sending = true
                                error = null
                                scope.launch {
                                    val failure = submit(picks)
                                    sending = false
                                    if (failure == null) onDismiss() else error = failure
                                }
                            },
                            enabled = picks.hasArtifact() && !sending,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("init-propose"),
                        ) { Text(if (sending) "Proposing…" else "Propose") }
                    }
                    if (!picks.hasArtifact()) {
                        Text("Pick at least one thing to set up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun InitCheck(tag: String, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun InitRadio(tag: String, label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun InitHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun InitNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
