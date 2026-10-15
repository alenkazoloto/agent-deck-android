package dev.agentdeck.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget

/** One row of the Agent pill: a vendor CLI, or an ACP catalog agent (whose [vendor] is only the default). */
private data class AgentPick(val vendor: AgentVendor, val acpAgentId: String? = null)

/**
 * Which agent a phone-started prompt runs on — the vendors, then the ACP agents the machine lists.
 * One composable for New chat and the schedule-create dialog, for the reason [ModelSelector] is one.
 *
 * The pill shows what the request will name: an ACP agent the machine no longer lists reads as the
 * vendor, exactly as [NewChat.acpAgentFor] sends it.
 */
@Composable
fun AgentSelector(
    target: NewChatTarget,
    vendors: List<AgentVendor>,
    hello: MobileHello?,
    onTarget: (NewChatTarget) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = "Agent:",
) {
    val acp = NewChat.acpAgents(hello)
    val current = NewChat.acpAgentFor(hello, target)
    Selector(
        options = vendors.map { SelectorOption(it.label(), AgentPick(it)) } +
            acp.map { SelectorOption(it.name, AgentPick(AgentVendor.CLAUDE, it.id)) },
        selected = AgentPick(target.vendor, current),
        onSelect = { pick -> onTarget(pick.acpAgentId?.let(target::withAcpAgent) ?: target.withVendor(pick.vendor)) },
        modifier = modifier,
        prefix = prefix,
    )
}
