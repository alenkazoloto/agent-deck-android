package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileMcpAdd
import kotlinx.coroutines.launch

/**
 * MCP servers › "Add server…": the desk's add dialog for a **remote** server, by address. A program to run is not
 * offered — the machine would start it at the next chat — and a token is only the *name* of an environment variable
 * on the machine, so nothing secret is typed here. [onAdd] answers null when the machine added it, else its own
 * sentence, which stays in the dialog with everything typed so a refusal costs nothing to fix. Shown only when the
 * machine advertises `mcp-add` to this phone; the fields are checked with the machine's own rules ([MobileMcpAdd]).
 *
 * [project] is the open project the list shows (empty when none is open), the only place Claude's project and local
 * scopes can be written; Codex has one merged configuration and takes no scope.
 */
@Composable
internal fun McpAddDialog(project: String, onAdd: suspend (MobileMcpAdd) -> String?, onDone: () -> Unit, onDismiss: () -> Unit) {
    var agent by remember { mutableStateOf("claude") }
    var scopeName by remember { mutableStateOf("user") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var variable by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scopes = if (project.isEmpty()) listOf("user") else listOf("user", "project", "local")
    val request = MobileMcpAdd(
        agent = agent,
        name = name.trim(),
        url = url.trim(),
        scope = if (agent == "claude") scopeName.takeIf { it in scopes } ?: "user" else "",
        project = if (agent == "claude" && scopeName != "user") project else "",
        tokenEnvVar = variable.trim(),
    )
    val urlBad = url.isNotBlank() && !MobileMcpAdd.validUrl(request.url)
    val nameBad = name.isNotBlank() && !MobileMcpAdd.validName(request.name)
    val variableBad = variable.isNotBlank() && !MobileMcpAdd.validEnvVar(request.tokenEnvVar)
    val ready = MobileMcpAdd.validName(request.name) && MobileMcpAdd.validUrl(request.url) && !variableBad && !busy
    // A Dialog around a full-width Surface, not an AlertDialog: its intrinsic measure of a single-line field never settles (`DiffBaseField`).
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add MCP server", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = agent == "claude", onClick = { agent = "claude" }, label = { Text("Claude") }, modifier = Modifier.testTag("mcp-add-claude"))
                        FilterChip(selected = agent == "codex", onClick = { agent = "codex" }, label = { Text("Codex") }, modifier = Modifier.testTag("mcp-add-codex"))
                    }
                    if (agent == "claude") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            scopes.forEach { option ->
                                FilterChip(
                                    selected = scopeName == option,
                                    onClick = { scopeName = option },
                                    label = { Text(option.replaceFirstChar { it.uppercase() }) },
                                    modifier = Modifier.testTag("mcp-add-scope-$option"),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = nameBad,
                        supportingText = if (nameBad) ({ Text("Letters, numbers, hyphens and underscores; not starting with a hyphen.") }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("mcp-add-name"),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Address") },
                        placeholder = { Text("https://example.com/mcp") },
                        singleLine = true,
                        isError = urlBad,
                        supportingText = if (urlBad) ({ Text("An http:// or https:// address with no user name or password in it.") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("mcp-add-url"),
                    )
                    OutlinedTextField(
                        value = variable,
                        onValueChange = { variable = it },
                        label = { Text("Token variable (optional)") },
                        singleLine = true,
                        isError = variableBad,
                        supportingText = { Text(if (variableBad) "Only the variable's name, like DOCS_TOKEN." else "The name of an environment variable on the machine that holds the bearer token. Never the token itself.") },
                        modifier = Modifier.fillMaxWidth().testTag("mcp-add-variable"),
                    )
                    failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mcp-add-failure")) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    TextButton(
                        enabled = ready,
                        onClick = {
                            busy = true
                            failure = null
                            scope.launch {
                                val answer = onAdd(request)
                                busy = false
                                if (answer == null) onDone() else failure = answer
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("mcp-add-confirm"),
                    ) { Text(if (busy) "Adding…" else "Add") }
                }
            }
        }
    }
}
