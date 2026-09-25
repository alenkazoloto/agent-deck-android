package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.github.claudeagents.core.mobile.MobileMcpServer
import com.github.claudeagents.core.mobile.MobileMcpServers

/**
 * Settings › Resources › "MCP servers": the servers the desk's Settings › MCP tables list, read-only.
 *
 * Read again each time the sheet opens and whenever another project is picked, so a server added on the
 * desk is there. The machine sends a description — the program or host, where a credential is configured
 * by name — never a command line, URL or key, so what is shown is all there is. Claude's health is not
 * shown: the desk checks it only on request, and a read from a phone must not start those connections.
 * The project chips appear only when more than one project is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpSheet(onLoad: suspend (String?) -> MobileMcpServers?, onDismiss: () -> Unit) {
    var project by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<MobileMcpServers?>(null) }
    var loading by remember { mutableStateOf(true) }
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(project) {
        loading = true
        loaded = load(project)
        loading = false
    }
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
                    if (result.servers.isEmpty() && result.notes.isEmpty()) Note("No MCP servers are configured on this machine.")
                    LazyColumn(Modifier.heightIn(max = 560.dp).testTag("mcp-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(result.servers, key = { it.agent + "/" + it.scope + "/" + it.name }) { McpRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpRow(server: MobileMcpServer) {
    Column(Modifier.fillMaxWidth().testTag("mcp-row").padding(top = 8.dp)) {
        Text(server.name, style = MaterialTheme.typography.bodyLarge)
        Detail(mcpServerLine(server))
        server.status?.let { Detail(it) }
        mcpCredentialLine(server)?.let { Detail(it) }
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

/** Only the names of where a credential is configured; null when there is none. */
internal fun mcpCredentialLine(server: MobileMcpServer): String? =
    server.secretKeys.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Credentials in ")
