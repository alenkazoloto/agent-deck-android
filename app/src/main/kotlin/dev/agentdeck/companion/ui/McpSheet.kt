package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileMcpAdd
import com.github.claudeagents.core.mobile.MobileMcpHealth
import com.github.claudeagents.core.mobile.MobileMcpRemove
import com.github.claudeagents.core.mobile.MobileMcpServer
import com.github.claudeagents.core.mobile.MobileMcpServers

/**
 * Settings › Resources › "MCP servers": the servers the desk's Settings › MCP tables list, read-only.
 *
 * Read again each time the sheet opens and whenever another project is picked, so a server added on the
 * desk is there. The machine sends a description — the program or host, where a credential is configured
 * by name — never a command line, URL or key, so what is shown is all there is. Claude's health is not
 * read with the list: the desk checks it only on request, and a read from a phone must not start those
 * connections. "Check connections" is that request ([onCheck], null on a machine without `mcp-health`); its
 * answer is kept only for the project it was asked about. The project chips appear only when more than
 * one project is open. "Remove…" is the desk's Settings › MCP Remove for one server ([onRemove], null on a machine
 * or a phone without `mcp-remove`): a dialog names the agent and scope first, the machine answers with its own
 * sentence when it did not remove it, and the list is read again either way so what is shown is what is there.
 * "Add server…" ([onAdd], null without `mcp-add`, which the machine offers only to a phone the owner granted it) adds a
 * remote server by address ([McpAddDialog]). A program to run and signing in stay on the desk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpSheet(
    onLoad: suspend (String?) -> MobileMcpServers?,
    onDismiss: () -> Unit,
    onCheck: (suspend (String?) -> MobileMcpHealth?)? = null,
    onRemove: (suspend (MobileMcpRemove) -> String?)? = null,
    onAdd: (suspend (MobileMcpAdd) -> String?)? = null,
) {
    var project by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<MobileMcpServers?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    val check by rememberUpdatedState(onCheck)
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Pair<String, MobileMcpHealth?>?>(null) }
    val remove by rememberUpdatedState(onRemove)
    var reload by remember { mutableStateOf(0) }
    var asking by remember { mutableStateOf<MobileMcpServer?>(null) }
    var removing by remember { mutableStateOf(false) }
    var removalNote by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(project, reload) {
        loading = true
        loaded = load(project)
        loading = false
    }
    asking?.let { server ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Remove \"${server.name}\"?") },
            text = { Text(mcpRemoveWords(server)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        removing = true
                        removalNote = null
                        val asked = MobileMcpRemove(server.agent, server.name, server.scope, loaded?.project.orEmpty())
                        scope.launch {
                            removalNote = remove?.invoke(asked)
                            removing = false
                            reload++
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mcp-remove-confirm"),
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
    }
    if (adding && onAdd != null) McpAddDialog(
        project = loaded?.project.orEmpty(),
        onAdd = onAdd,
        onDone = { adding = false; reload++ },
        onDismiss = { adding = false },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("MCP servers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val result = loaded
            if (result != null && result.projects.size > 1) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.projects.forEach { open ->
                        FilterChip(selected = open.path == result.project, onClick = { project = open.path }, label = { Text(open.name) })
                    }
                }
            }
            when {
                loading && result == null -> Note("Reading the machine's MCP servers…")
                result == null -> Note("The machine did not answer. Close this and try again.")
                else -> {
                    result.notes.forEach { Note(it) }
                    removalNote?.let { Note(it) }
                    if (onAdd != null) TextButton(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("mcp-add")) { Text("Add server…") }
                    val answer = checked?.takeIf { it.first == result.project }
                    if (check != null && result.servers.any { it.agent == "claude" }) {
                        TextButton(
                            enabled = !checking,
                            onClick = {
                                val asked = result.project
                                checking = true
                                scope.launch {
                                    checked = asked to check?.invoke(asked.takeIf { it.isNotEmpty() })
                                    checking = false
                                }
                            },
                            modifier = Modifier.testTag("mcp-check"),
                        ) { Text(if (checking) "Checking connections…" else "Check connections") }
                        if (answer != null && answer.second == null) Note("The machine did not answer. Try again.")
                        answer?.second?.failure?.let { Note(it) }
                    }
                    val states = answer?.second?.servers.orEmpty().associate { it.name to it.state }
                    if (result.servers.isEmpty() && result.notes.isEmpty()) Note("No MCP servers are configured on this machine.")
                    LazyColumn(Modifier.heightIn(max = 560.dp).testTag("mcp-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(result.servers, key = { it.agent + "/" + it.scope + "/" + it.name }) {
                            McpRow(it, states[it.name].takeIf { _ -> it.agent == "claude" }, if (remove != null && !removing) ({ asking = it }) else null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpRow(server: MobileMcpServer, health: String?, onRemove: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("mcp-row").padding(top = 8.dp)) {
        Text(server.name, style = MaterialTheme.typography.bodyLarge)
        Detail(mcpServerLine(server))
        server.status?.let { Detail(it) }
        health?.let { Detail(mcpHealthWords(it)) }
        mcpCredentialLine(server)?.let { Detail(it) }
        if (onRemove != null) {
            TextButton(
                onClick = onRemove,
                modifier = Modifier.heightIn(min = 48.dp).testTag("mcp-remove-${server.agent}-${server.scope}-${server.name}"),
            ) { Text("Remove…", color = MaterialTheme.colorScheme.error) }
        }
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
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** "Claude · user scope · http · mcp.figma.com": whose it is, where it is declared and what it talks to, all words. */
internal fun mcpServerLine(server: MobileMcpServer): String = listOfNotNull(
    when (server.agent) {
        "codex" -> "Codex"
        "claude" -> "Claude"
        else -> server.agent.replaceFirstChar { it.uppercase() }
    },
    server.scope.takeIf { it.isNotEmpty() }?.let { "$it scope" },
    server.transport.takeIf { it.isNotEmpty() },
    server.target.takeIf { it.isNotEmpty() },
).joinToString(" · ")

/** What removing does, in the agent's and scope's own words, so the dialog never says "Claude" for a Codex server. */
internal fun mcpRemoveWords(server: MobileMcpServer): String {
    val where = if (server.agent == "codex") "Codex's configuration" else "Claude's ${server.scope} scope"
    return "Removes it from $where on the machine. Chats started afterwards will not have it; adding it back is done in the IDE."
}

/** Only the names of where a credential is configured; null when there is none. */
internal fun mcpCredentialLine(server: MobileMcpServer): String? =
    server.secretKeys.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Credentials in ")

/** The desk's Health column in the CLI's own words; a state a newer machine adds is shown as sent rather than dropped. */
internal fun mcpHealthWords(state: String): String = when (state) {
    "connected" -> "Connected"
    "failed" -> "Failed to connect"
    "needs-auth" -> "Needs authentication"
    "pending" -> "Pending approval"
    else -> state
}
