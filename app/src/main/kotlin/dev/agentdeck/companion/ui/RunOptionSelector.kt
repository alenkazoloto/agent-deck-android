package dev.agentdeck.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.NewChat

/**
 * Which effort rung a phone prompt names. One composable for New chat, the schedule dialog and
 * the conversation composer, for the reason [ModelSelector] is one: all three fill one field.
 */
@Composable
fun EffortSelector(
    hello: MobileHello?,
    vendor: AgentVendor,
    /** The rung in use, or null for the machine's own default. */
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    val rows = RunOptionRows.effort(hello, vendor, selected)
    if (rows.isEmpty()) return
    Selector(options = rows, selected = selected, onSelect = onSelect, modifier = modifier, prefix = prefix)
}

/** [EffortSelector]'s twin for the permission mode (a Codex sandbox). */
@Composable
fun ModeSelector(
    hello: MobileHello?,
    vendor: AgentVendor,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = "Mode",
) {
    val rows = RunOptionRows.mode(hello, vendor, selected)
    if (rows.isEmpty()) return
    Selector(options = rows, selected = selected, onSelect = onSelect, modifier = modifier, prefix = prefix)
}

/**
 * The desk composer's two checkbox rows — Fast mode and Thinking — as one three-way pill each
 * (Default follows the desk, On, Off). Offered for [vendors] — Fast for both, Thinking for Claude
 * alone — and only against a machine advertising [MobileProtocol.Capability.RUN_TOGGLES]: an
 * older plugin would drop the pick unread.
 */
@Composable
fun RunToggleSelector(
    hello: MobileHello?,
    vendor: AgentVendor,
    label: String,
    selected: Boolean?,
    onSelect: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
    vendors: Set<AgentVendor> = setOf(AgentVendor.CLAUDE),
) {
    if (vendor !in vendors || !RunOptionRows.togglesOffered(hello)) return
    Selector(
        options = RunOptionRows.toggle(),
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        prefix = "$label:",
    )
}

/**
 * The rows [EffortSelector] and [ModeSelector] offer, and what a request may carry. As in
 * [ModelRows]: a null "Default" row first, and a value in use that the ladder lacks shown as
 * itself. Unlike it, no capability means no rows even for a value in use: [NewChat.forWire] sends
 * nothing the machine did not advertise, so a pill would show a pick that goes nowhere.
 */
object RunOptionRows {

    const val DEFAULT_EFFORT = "Default effort"
    const val DEFAULT_MODE = "Default"

    /** Fast is Claude's Opus flag and Codex's `priority` tier; Thinking is Claude's alone. */
    val FAST_VENDORS: Set<AgentVendor> = setOf(AgentVendor.CLAUDE, AgentVendor.CODEX)

    fun togglesOffered(hello: MobileHello?): Boolean = advertises(hello, MobileProtocol.Capability.RUN_TOGGLES)

    fun toggle(): List<SelectorOption<Boolean?>> = listOf(
        SelectorOption<Boolean?>("Default", null),
        SelectorOption<Boolean?>("On", true),
        SelectorOption<Boolean?>("Off", false),
    )

    fun effort(hello: MobileHello?, vendor: AgentVendor, inUse: String?): List<SelectorOption<String?>> {
        val ladder = if (advertises(hello, MobileProtocol.Capability.EFFORT)) hello?.effort?.get(vendor) else null
        return rows(ladder.orEmpty(), inUse, DEFAULT_EFFORT)
    }

    fun mode(hello: MobileHello?, vendor: AgentVendor, inUse: String?): List<SelectorOption<String?>> {
        val ladder = if (advertises(hello, MobileProtocol.Capability.PERMISSION_MODES)) hello?.permissionModes?.get(vendor) else null
        return rows(ladder.orEmpty(), inUse, DEFAULT_MODE)
    }

    private fun advertises(hello: MobileHello?, capability: String): Boolean =
        hello != null && capability in hello.capabilities

    private fun rows(ladder: List<MobileModelOption>, inUse: String?, defaultLabel: String): List<SelectorOption<String?>> {
        if (ladder.isEmpty()) return emptyList()
        val stranger = inUse?.takeIf { value -> ladder.none { it.slug == value } }
        return listOf(SelectorOption<String?>(defaultLabel, null)) +
            ladder.map { SelectorOption<String?>(it.label, it.slug) } +
            listOfNotNull(stranger?.let { SelectorOption<String?>(it, it) })
    }
}
