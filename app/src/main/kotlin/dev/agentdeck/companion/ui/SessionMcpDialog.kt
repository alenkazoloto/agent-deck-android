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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileSessionMcpServer
import dev.agentdeck.companion.data.SessionMcpSheet

/**
 * A Claude chat's MCP servers, the desk composer's "Choose which MCP servers this chat uses"
 * (`/v1/session-mcp`): every server the chat's account can load with a tick, the program or host
 * beside its name. A tick is one change the machine answers; "Use all servers" is offered only while
 * the chat is limited.
 */
@Composable
internal fun SessionMcpDialog(
    sheet: SessionMcpSheet,
    onToggle: (String) -> Unit,
    onUseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("session-mcp-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("MCP servers", style = MaterialTheme.typography.headlineSmall)
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
                // One scrolling body under the fixed buttons: at 200% text the list outgrows the dialog.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val servers = sheet.servers
                    when {
                        servers == null && sheet.error == null -> Notice("Reading this conversation…")
                        servers == null -> Unit
                        servers.isEmpty() && sheet.refused == null -> Notice("No MCP servers are configured.")
                        else -> servers.forEach { server -> ServerRow(server, enabled = !sheet.busy, onToggle = { onToggle(server.name) }) }
                    }
                    sheet.refused?.let { Notice(it, error = true, tag = "session-mcp-refused") }
                    sheet.error?.let { Notice(it, error = true) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    if (sheet.narrowed) {
                        TextButton(onClick = onUseAll, enabled = !sheet.busy, modifier = Modifier.testTag("session-mcp-all")) { Text("Use all servers") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(server: MobileSessionMcpServer, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = server.on, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() },
        ).padding(horizontal = 12.dp).testTag("session-mcp-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = server.on, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Text(server.name, style = MaterialTheme.typography.bodyLarge)
            val detail = listOf(server.scope, server.transport, server.target).filter { it.isNotBlank() }.joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false, tag: String = "session-mcp-notice") {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp).testTag(tag),
    )
}
