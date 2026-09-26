package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileRepositorySetupRequest
import dev.agentdeck.companion.data.RepositorySheet

/** What the repository sheet calls back into; one bundle so the host's call stays readable. */
class RepositoryActions(
    val onInput: (String) -> Unit,
    val onHost: (String) -> Unit,
    val onProtocol: (String) -> Unit,
    val onVisibility: (String) -> Unit,
    val onParent: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onOpenCloned: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The desk's "Clone Repository" and "Publish Repository" dialogs as a sheet. Clone takes the desk's
 * one field — a Git URL, a repository page or `owner/repository` — and lands beside the machine's
 * open projects; Publish names the hosted repository this checkout gets. A landed clone offers the
 * desk notification's "Open", on the machine; a landed publish its page, as a link the reader taps.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepositorySheetView(sheet: RepositorySheet, actions: RepositoryActions) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("repository-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (sheet.publish) "Publish repository" else "Clone repository", style = MaterialTheme.typography.titleMedium)
            if (sheet.publish) {
                Text(
                    sheet.projectPath.trimEnd('/').substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val options = sheet.options
            if (options == null || sheet.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            val done = sheet.done
            when {
                done != null -> Done(sheet, actions)
                options == null -> Unit
                options.hosts.isEmpty() -> Unit
                else -> Form(sheet, actions)
            }
            sheet.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("repository-error"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(sheet: RepositorySheet, actions: RepositoryActions) {
    val options = sheet.options ?: return
    val host = sheet.chosenHost
    val editable = !sheet.working
    OutlinedTextField(
        value = sheet.input,
        onValueChange = actions.onInput,
        label = { Text("Repository") },
        singleLine = true,
        enabled = editable,
        supportingText = {
            Text(
                if (sheet.publish) "As ${host?.hint ?: "owner/repository"}."
                else "A Git URL, a repository page, or ${host?.hint ?: "owner/repository"}.",
            )
        },
        modifier = Modifier.fillMaxWidth().testTag("repository-input"),
    )
    // Labelled chip rows rather than pills: a pill trims its value at 200 % text, and Public
    // against Private is the one choice here that must never be read as "Pr…".
    if (sheet.publish || !sheet.inputIsUrl) {
        ChoiceRow("Host", options.hosts.map { SelectorOption(it.name, it.id) }, sheet.host, actions.onHost, editable)
    }
    if (sheet.publish && host?.visibilityApplies != false) {
        ChoiceRow(
            "Visibility",
            listOf(SelectorOption("Private", MobileRepositorySetupRequest.PRIVATE), SelectorOption("Public", MobileRepositorySetupRequest.PUBLIC)),
            sheet.visibility, actions.onVisibility, editable,
        )
    }
    if (sheet.publish || sheet.protocolApplies) {
        ChoiceRow(
            "Protocol",
            listOf(SelectorOption("HTTPS", MobileRepositorySetupRequest.HTTPS), SelectorOption("SSH", MobileRepositorySetupRequest.SSH)),
            sheet.protocol, actions.onProtocol, editable,
        )
    }
    if (!sheet.publish) {
        Selector(
            options = options.cloneParents.map { SelectorOption(it, it) },
            selected = sheet.parentDir,
            onSelect = actions.onParent,
            prefix = "Clone into:",
        )
    }
    val note = if (sheet.publish) {
        listOfNotNull(
            "Creates the ${host?.name ?: ""} repository ${sheet.input.trim()} and adds it as origin, then pushes this branch."
                .takeIf { sheet.input.isNotBlank() },
            host?.visibilityNote,
        ).joinToString(" ")
    } else if (options.cloneParents.isEmpty()) {
        "The machine has no directory to clone into."
    } else {
        "Clones into a new directory in ${sheet.parentDir}."
    }
    if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(
        onClick = actions.onSubmit,
        enabled = sheet.canSubmit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("repository-submit"),
    ) {
        Text(
            when {
                sheet.working && sheet.publish -> "Publishing…"
                sheet.working -> "Cloning…"
                sheet.publish -> "Publish"
                else -> "Clone"
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(label: String, choices: List<SelectorOption<String>>, selected: String, onSelect: (String) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice.value == selected,
                    onClick = { onSelect(choice.value) },
                    label = { Text(choice.label) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun Done(sheet: RepositorySheet, actions: RepositoryActions) {
    val done = sheet.done ?: return
    Text(done.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("repository-done"))
    done.url?.let { url ->
        val uri = LocalUriHandler.current
        TextButton(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open in browser") }
    }
    if (done.path != null) {
        val opened = done.opened
        if (opened == null) {
            Button(
                onClick = actions.onOpenCloned,
                enabled = !sheet.working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("repository-open"),
            ) { Text("Open on the machine") }
        } else {
            Text(opened, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    TextButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
}
