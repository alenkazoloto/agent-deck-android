package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileAcpAgentAction
import com.github.claudeagents.core.mobile.MobileAcpAgents
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the list is read again while an agent is starting or signing in, so the sheet says how it ended. */
private const val WORKING_POLL_MS = 2_000L

/**
 * Settings › Resources › "ACP agents": the desk's Settings › Connections table for the agents switched on there — each one's
 * state in the desk's own words and the one thing the desk would offer about it.
 *
 * "Connect" ([onAct], null on a machine or a phone without `acp-agent-actions`, which the machine offers only under the owner's
 * per-phone grant) starts the agent the way the desk's Connect does; "Sign in with …" names one of the methods the agent itself
 * offered, the desk's popup. Neither waits for the answer — a start waits on the agent and a sign-in on a browser on the machine —
 * so the row says what the machine is doing and the list is read again every couple of seconds until it stops. The machine's
 * own sentence is shown when it started nothing. An agent's command and where it runs are the desk's to edit and are never shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AcpAgentsSheet(
    onLoad: suspend () -> MobileAcpAgents?,
    onDismiss: () -> Unit,
    onAct: (suspend (MobileAcpAgentAction) -> String?)? = null,
) {
    var loaded by remember { mutableStateOf<MobileAcpAgents?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val load by rememberUpdatedState(onLoad)
    val act by rememberUpdatedState(onAct)
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        loading = true
        loaded = load()
        loading = false
        // A busy row is the machine mid-action: read again until it has an outcome to show, so a sign-in finished in the browser turns green here.
        while (loaded?.agents?.any { it.severity == "busy" } == true) {
            delay(WORKING_POLL_MS)
            loaded = load() ?: loaded
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("ACP agents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val result = loaded
            when {
                loading && result == null -> AgentNote("Reading the machine's agents…")
                result == null -> AgentNote("The machine did not answer. Close this and try again.")
                result.agents.isEmpty() -> AgentNote("No ACP agents are switched on in Settings › Connections on this machine.")
                else -> {
                    note?.let { AgentNote(it) }
                    LazyColumn(Modifier.heightIn(max = 560.dp).testTag("acp-agents-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(result.agents, key = { it.id }) { agent ->
                            AgentRow(agent, act != null) { asked ->
                                note = null
                                scope.launch {
                                    note = act?.invoke(asked)
                                    reload++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRow(agent: MobileAcpAgents.Agent, canAct: Boolean, onAct: (MobileAcpAgentAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("acp-agent-row").padding(top = 8.dp)) {
        Text(agent.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            agent.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (agent.severity == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("acp-agent-state-${agent.id}"),
        )
        agent.advice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (canAct) {
            when (agent.action) {
                "connect" -> AgentButton("Connect", "acp-agent-connect-${agent.id}") { onAct(MobileAcpAgentAction(agent.id, MobileAcpAgentAction.CONNECT)) }
                "sign-in" -> agent.methods.forEach { method ->
                    AgentButton("Sign in with ${method.name}", "acp-agent-signin-${agent.id}-${method.id}") {
                        onAct(MobileAcpAgentAction(agent.id, MobileAcpAgentAction.SIGN_IN, method.id))
                    }
                    method.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AgentButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(label) }
}

@Composable
private fun AgentNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
