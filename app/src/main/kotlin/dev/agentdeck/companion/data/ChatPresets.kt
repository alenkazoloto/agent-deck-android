package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobilePreset
import com.github.claudeagents.core.mobile.MobileProtocol

/**
 * The desk's `/p` on an open chat: a saved preset's model, effort and mode become the picks of the next message.
 *
 * Only the cells travel. A chat cannot change agent or account under its feet (the desk refuses `/p` a preset of
 * another agent or account, and "Continue on another account" is its own route), and a preset's instructions and CLI
 * options are read only when a chat *starts* from it — New chat's Preset pill does that — so none is claimed here.
 */
object ChatPresets {

    /** The presets this vendor's chat can take: none unless the machine advertised `agent-presets`. */
    fun offered(hello: MobileHello?, vendor: AgentVendor): List<MobilePreset> =
        if (hello == null || MobileProtocol.Capability.AGENT_PRESETS !in hello.capabilities) emptyList()
        else hello.presets.filter { it.vendor == vendor }

    /** The desk's `find`: the exact name, else the first whose name starts with, then contains, [query] — case aside. */
    fun find(presets: List<MobilePreset>, query: String): MobilePreset? {
        val wanted = query.trim().ifEmpty { return null }
        return presets.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: presets.firstOrNull { it.name.startsWith(wanted, ignoreCase = true) }
            ?: presets.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
    }

    /** A preset naming no model or effort leaves Default, as the desk's Apply does for an empty cell. */
    fun picks(preset: MobilePreset): List<Pair<ComposerPicks.Field, String?>> = listOf(
        ComposerPicks.Field.MODEL to preset.model.ifEmpty { null },
        ComposerPicks.Field.EFFORT to preset.effort.ifEmpty { null },
        ComposerPicks.Field.MODE to preset.permissionMode,
    )

    fun applied(preset: MobilePreset): String = buildString {
        append("Preset ${preset.name} set for the next message.")
        if (preset.extras.isNotEmpty()) append(" ${preset.extras.joinToString(" · ")} apply only to a new chat.")
    }

    fun notFound(query: String): String = "No preset matches “${query.trim()}”."
}
